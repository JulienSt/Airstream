package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.{DeliveryRejected, SubmissionRejected, WaitInsideTurn}
import com.raquo.airstream.platform.{AtomicCounter, DevTools, DiagnosticLevel, DiagnosticSinks, JsArray, RuntimePermit, RuntimeTimers}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/** A runtime is an identity plus the state that Airstream used to keep in process-wide singletons: the transaction
  * stack, the shared-start batching, the update-id counter, the error routing and the configuration. Two runtimes never
  * share any of it, so two graphs in two runtimes cannot interfere.
  *
  * Entry protocol: every access to a runtime's graph happens inside `run`. The outermost `run` on a thread acquires the
  * runtime's execution permit and installs the runtime as the current context, so observables and transactions created
  * inside capture it; it restores both in `finally`, whatever the body does. A nested `run` on the same thread
  * is free: the body runs directly, which is what keeps `Var.set` inside an observer as cheap as it is today.
  *
  * Two things are rejected before the runtime's state is touched: a thread the permit does not admit
  * ([[ForeignThreadEntry]]) and a synchronous entry into this runtime from inside another runtime's
  * turn ([[CrossRuntimeAccess]]). Both are exceptions at the call site; neither blocks.
  *
  * The default runtime has a floating permit: whichever thread enters it first holds it until that entry returns, and the
  * next thread may enter afterwards. A confined runtime is bound to the thread that created it. Scala.js has one thread,
  * so there the permit only tracks whether the runtime is entered.
  *
  * What admits a thread is the runtime's [[RuntimeGate]]. The default runtime's gate is its permit alone; the permits, the
  * per-thread context and the mailbox that exist only because a second runtime can exist come with the gate the factories
  * build, so a program that never calls them links and runs none of it.
  *
  * A scheduled runtime (`Runtime.scheduled`) has a mailbox instead: `submit` admits a command from any thread and its
  * executor runs the turns, one at a time; `run` is rejected there unless it is reentrant, inside a
  * turn. Both strategies share the turn: a command runs under the permit with this runtime as the current context, and
  * its receipt settles after the command's synchronous work has drained.
  */
final class Runtime private (
  gateFor: Runtime => RuntimeGate,
  initialUnhandledErrorCallbacks: JsArray[Throwable => Unit]
) extends Named {

  /** How this runtime admits a thread. Built first by the function its factory passes in, never chosen by a constructor branch:
    * only the factories contain `new ManagedGate`, so a program that calls none of them instantiates one gate class.
    */
  private val gate: RuntimeGate = gateFor(this)

  private val unhandledErrorCallbacks: JsArray[Throwable => Unit] = initialUnhandledErrorCallbacks

  /** The mailbox of a scheduled runtime; null for a direct one, which runs a submitted command at once. Inline, so a program
    * whose only gate is the default one folds every mailbox branch away and links no mailbox.
    */
  @inline private[airstream] def mailbox: Mailbox = gate.mailbox

  /** Ids of the commands submitted to this runtime, direct or scheduled; 64-bit, never reused. */
  private val commandIds: AtomicCounter = new AtomicCounter

  /** The id of the command whose turn the permit holder is running, or `Runtime.NoCommand` outside a command. */
  private var activeCommand: Long = Runtime.NoCommand

  /** The trace this runtime reports its observation points to, or null for none. */
  private var sink: RuntimeTrace = null

  /** Report this runtime's observation points to `trace`; see [[RuntimeTrace]]. Install it before the runtime is used. */
  def traceTo(trace: RuntimeTrace): Unit = {
    sink = trace
  }

  /** Where this runtime is in its lifecycle. A direct runtime stays open; a scheduled runtime fails when its scheduler
    * cannot continue.
    */
  def state: RuntimeState = gate.state

  /** Execute `body` inside this runtime: on the permit-holding thread with this runtime as the current context.
    *
    * @throws ForeignThreadEntry when the calling thread may not take the permit
    * @throws CrossRuntimeAccess when the calling thread is inside another runtime's turn
    */
  def run[A](body: => A): A = {
    if (gate.isHeldByCurrentThread) {
      body
    } else {
      val entry = gate.tryEnter(this)
      if (entry eq null) {
        throw gate.refusal(this)
      }
      entered(gate)(entry, body)
    }
  }

  /** Run `body` in the outermost entry `entry` of `g` just made for the calling thread, and exit it whatever the body does. */
  private def entered[A](g: RuntimeGate)(entry: g.Entry, body: => A): A = {
    traceAcquired()
    try {
      body
    } finally {
      g.exit(entry)
      traceReleased()
    }
  }

  /** Schedule `transaction` inside this runtime: the entry of `run`, written out for the one body every root write runs, so
    * that path allocates no closure and makes no call through a function value.
    */
  private[airstream] def schedule(transaction: Transaction): Unit = {
    if (gate.isHeldByCurrentThread) {
      transaction.scheduleInside(this)
    } else {
      val entry = gate.tryEnter(this)
      if (entry eq null) {
        throw gate.refusal(this)
      }
      traceAcquired()
      try {
        transaction.scheduleInside(this)
      } finally {
        gate.exit(entry)
        traceReleased()
      }
    }
  }

  /** The calling thread just took the permit and installed the context: report it to the trace, when one is installed. */
  private def traceAcquired(): Unit = {
    if (sink ne null) {
      sink.permitAcquired(this)
    }
  }

  /** The calling thread just cleared the context and gave the permit back: report it to the trace, when one is installed. */
  private def traceReleased(): Unit = {
    if (sink ne null) {
      sink.permitReleased(this)
      sink.contextRestored(this)
    }
  }

  /** Hand an external completion to this runtime: the ingress of every asynchronous adapter.
    *
    * A Future or promise callback, a publisher's signal, a custom source's fire from another thread or a timer's readiness
    * runs wherever its executor, publisher or event loop runs it. `body` is everything that callback does to the graph,
    * pending flags included, and it runs only under this runtime's permit:
    *
    *   - on the permit-holding thread, at once: a callback that an inline execution context or a synchronous source runs
    *     inside the current turn keeps today's behaviour, and a transaction it creates during a shared start still waits
    *     for the start to finish;
    *   - on a scheduled runtime, from any other thread, as a submitted command whose turn runs `body` later; an exception
    *     `body` throws there goes to this runtime's unhandled-error channel, since nobody holds the command's receipt;
    *   - on a direct runtime, as an entry on the calling thread when it may take the permit now, exactly like `run`.
    *
    * When the runtime cannot take the completion (a direct runtime held by another thread or bound to one, a caller inside
    * another runtime's turn, a full mailbox, a failed runtime), the callback is not lost silently: `DeliveryRejected` goes to
    * this runtime's unhandled-error channel, because the callback has no receipt to fail.
    */
  private[airstream] def deliver(body: => Unit): Unit = {
    if (gate.isHeldByCurrentThread) {
      body
    } else if (mailbox ne null) {
      val receipt = mailbox.submit(commandIds.incrementAndGet(), () => deliveredInTurn(body))
      receipt.accepted.value.flatMap(_.failed.toOption).foreach(refuseDelivery)
    } else {
      val entry = gate.tryEnter(this)
      if (entry eq null) {
        refuseDelivery(gate.refusal(this))
      } else {
        entered(gate)(entry, body)
      }
    }
  }

  /** Run a delivered completion inside its turn; its failure is reported here, because its receipt has no reader. */
  private def deliveredInTurn(body: => Unit): Unit = {
    try {
      body
    } catch {
      case NonFatal(err) => sendUnhandledError(err)
    }
  }

  /** Report a completion this runtime could not take to its unhandled-error channel, from the delivering thread; after a
    * close it is suppressed instead, because the runtime ended on purpose.
    */
  private def refuseDelivery(cause: Throwable): Unit = {
    if (!isShutDown) {
      sendUnhandledError(DeliveryRejected(this, cause))
    }
  }

  /** Submit a command to this runtime from any thread. The receipt's `accepted` future is settled
    * when this returns; its `completed` future settles after the command's turn has drained.
    *
    * A scheduled runtime admits the command into its mailbox, in FIFO order per runtime, and runs it as a turn on its
    * executor; it rejects the command, with no effect, when the mailbox is at capacity or the runtime failed. A direct
    * runtime runs the command at once as a turn on the calling thread, and rejects it when that thread may not enter
    * (another thread holds the permit, or the caller is inside another runtime's turn). A direct runtime's own turn may
    * submit to itself: the command then runs inline, and the transactions it creates are children of the running one,
    * as every nested write is.
    */
  def submit(command: => Unit): Receipt = submitKeeping(0)(command)

  /** Submit while leaving `reservedSlots` mailbox slots available to producers that use a smaller reservation.
    * The same atomic capacity counter covers every producer; this changes admission only, never FIFO delivery.
    * A refused command has no effect and may be retried. Direct runtimes have no queue and run it as an ordinary submit.
    *
    * @throws IllegalArgumentException if the reservation is negative or leaves no room in a scheduled mailbox
    */
  def submitKeeping(reservedSlots: Int)(command: => Unit): Receipt = {
    require(reservedSlots >= 0, s"reservedSlots must be nonnegative, got $reservedSlots")
    val id = commandIds.incrementAndGet()
    if (mailbox ne null) {
      mailbox.submit(id, () => command, reservedSlots)
    } else {
      submitDirect(id, () => command)
    }
  }

  private def submitDirect(id: Long, body: () => Unit): Receipt = {
    val completed = Promise[Unit]()
    if (gate.isHeldByCurrentThread) {
      try {
        body()
        completed.success(())
      } catch {
        case NonFatal(err) => completed.failure(err)
      }
      new Receipt(id, Future.unit, completed.future)
    } else {
      val entry = gate.tryEnter(this)
      if (entry eq null) {
        val rejected = SubmissionRejected(this, gate.refusal(this).getMessage)
        completed.failure(rejected)
        new Receipt(id, Future.failed(rejected), completed.future)
      } else {
        runClaimedTurn(new Mailbox.Command(id, body, completed), gate)(entry)
        new Receipt(id, Future.unit, completed.future)
      }
    }
  }

  /** Run `command` as a turn on the calling thread, for the mailbox's drainer: claim the permit, run, release, settle.
    *
    * @throws IllegalStateException when the permit could not be claimed (a dispatcher ran the drain on another thread) or
    *   the turn left the transaction engine torn; the mailbox then fails the runtime
    */
  private[airstream] def executeTurn(command: Mailbox.Command): Unit = {
    val entry = gate.claim(this)
    if (entry eq null) {
      throw new IllegalStateException(
        s"${displayName}: a drain ran on thread ${gate.holderName.fold("?")(identity)} while the permit " +
          gate.boundThreadName.fold("was held")(name => s"is bound to `$name`")
      )
    }
    runClaimedTurn(command, gate)(entry)
  }

  /** The turn itself, with the permit already taken and the context installed by `entry` of `g`: install the command id,
    * run the body, restore everything on every exit, then settle the receipt. The receipt settles after the permit is
    * released, so a waiter that wakes finds the runtime idle; a fatal error or a torn engine settles the receipt and then
    * propagates.
    */
  private def runClaimedTurn(command: Mailbox.Command, g: RuntimeGate)(entry: g.Entry): Unit = {
    activeCommand = command.id
    if (sink ne null) {
      sink.permitAcquired(this)
      sink.turnStarted(this, command.id)
    }
    var failure: Throwable = null
    try {
      command.body()
    } catch {
      case err: Throwable => failure = err
    } finally {
      activeCommand = Runtime.NoCommand
      g.exit(entry)
      traceReleased()
    }
    val torn =
      if (transactions.isClearState) null
      else new IllegalStateException(s"${displayName}: the transaction engine is not clear after command ${command.id}")
    if (sink ne null) {
      sink.turnCompleted(this, command.id)
    }
    if (failure eq null) {
      if (torn eq null) {
        command.completed.trySuccess(())
      } else {
        command.completed.tryFailure(torn)
      }
    } else {
      command.completed.tryFailure(failure)
    }
    if (torn ne null) {
      throw torn
    }
    if ((failure ne null) && !NonFatal(failure)) {
      throw failure
    }
  }

  /** The mailbox failed this runtime: report it once through the unhandled-error channel, so a host without a receipt in
    * hand still learns that accepted work settled as failed, and to the trace.
    */
  private[airstream] def reportFailure(cause: Throwable): Unit = {
    if (sink ne null) {
      sink.runtimeFailed(this, cause)
    }
    sendUnhandledError(AirstreamError.RuntimeFailed(this, cause))
  }

  /** The command whose turn the permit holder is running, for the trace. */
  private[airstream] def tracedCommand: Option[Long] = {
    if (activeCommand == Runtime.NoCommand) None else Some(activeCommand)
  }

  /** Report a transaction created in this runtime to the trace, when one is installed. */
  private[airstream] def traceTransactionCreated(): Unit = {
    if (sink ne null) {
      sink.transactionCreated(this, tracedCommand)
    }
  }

  /** Report a managed task's delivered result to the trace, when one is installed. */
  private[airstream] def traceTaskDelivered(taskId: Long): Unit = {
    if (sink ne null) {
      sink.taskDelivered(this, taskId)
    }
  }

  /** Report a managed task's suppressed result to the trace, when one is installed. */
  private[airstream] def traceTaskSuppressed(taskId: Long): Unit = {
    if (sink ne null) {
      sink.taskSuppressed(this, taskId)
    }
  }

  /** Report a bridge message applied in this runtime to the trace, when one is installed. */
  private[airstream] def traceBridgeMessageDelivered(sequence: Long): Unit = {
    if (sink ne null) {
      sink.bridgeMessageDelivered(this, sequence)
    }
  }

  /** True when `submit` from another thread queues the command instead of running it on that thread: a scheduled or a
    * dispatched runtime. A bridge delivers only into such a runtime.
    */
  private[airstream] def admitsSubmissions: Boolean = mailbox ne null

  /** Report a requested external-observer removal to the trace, when one is installed. */
  private[airstream] def traceRemovalRequested(observer: Observer[?]): Unit = {
    if (sink ne null) {
      sink.removalRequested(this, observer)
    }
  }

  /** Report an applied external-observer removal to the trace, when one is installed. */
  private[airstream] def traceRemovalApplied(observer: Observer[?]): Unit = {
    if (sink ne null) {
      sink.removalApplied(this, observer)
    }
  }

  /** Close this runtime gracefully and return the close's receipt.
    *
    *   1. External admission ends at once and atomically: a `submit` either was admitted before the close or is rejected.
    *   2. Every accepted command still runs, in order, with its synchronous descendants, and settles its receipt.
    *   3. At one serialized point after them, under the permit, the runtime's cleanup runs once: its task scopes close,
    *      its pending timers are cancelled and its bridge endpoints are released. A failure there is reported through the
    *      unhandled-error channel; it does not roll back anything the commands did.
    *   4. The runtime is `Closed`, and the receipt's `completed` future succeeds.
    *
    * Afterwards every submission and every delivery is turned away: a late Future result, timer tick or bridge message is
    * suppressed without an error, since the runtime ended on purpose. The executor is not touched: a runtime never owns the
    * executor it was given, so a pool shared with other runtimes stays usable. The default runtime cannot be
    * closed; its receipt is rejected.
    */
  def close(): Receipt = {
    val id = commandIds.incrementAndGet()
    gate match {
      case _: DefaultGate =>
        val rejected = SubmissionRejected(this, "the default runtime cannot be closed")
        new Receipt(id, Future.failed(rejected), Future.failed(rejected))
      case managed: ManagedGate =>
        if (managed.mailbox ne null) {
          new Receipt(id, Future.unit, managed.mailbox.close())
        } else {
          closeDirect(id, managed)
        }
    }
  }

  /** A direct runtime has no queue to drain: its close runs the cleanup at once on the calling thread, under the permit,
    * when that thread may enter, inline when it is inside the runtime's own turn (the turn then finishes, and nothing
    * enters after it). A thread that may not enter gets a rejected receipt; a second close finds the runtime closed.
    */
  private def closeDirect(id: Long, managed: ManagedGate): Receipt = {
    if (managed.state ne RuntimeState.Open) {
      new Receipt(id, Future.unit, Future.unit)
    } else if (managed.isHeldByCurrentThread) {
      finishDirectClose(managed)
      new Receipt(id, Future.unit, Future.unit)
    } else {
      val entry = managed.tryEnter(this)
      if (entry eq null) {
        val rejected = SubmissionRejected(this, managed.refusal(this).getMessage)
        new Receipt(id, Future.failed(rejected), Future.failed(rejected))
      } else {
        entered(managed)(entry, finishDirectClose(managed))
        new Receipt(id, Future.unit, Future.unit)
      }
    }
  }

  /** Under the permit: cut admission, release what the runtime owns, retire the permit, and mark the runtime closed. */
  private def finishDirectClose(managed: ManagedGate): Unit = {
    managed.advanceDirectState(RuntimeState.Closing)
    traceAdmissionClosed()
    try {
      releaseOwned()
    } finally {
      managed.retire()
      managed.advanceDirectState(RuntimeState.Closed)
      traceClosed()
    }
  }

  /** Run the cleanup of a close, on the drainer's thread, with the permit claimed and the context installed for it and
    * restored afterwards.
    *
    * @throws IllegalStateException when the permit could not be claimed (a dispatcher ran the drain on another thread)
    */
  private[airstream] def closeResources(managed: ManagedGate): Unit = {
    val entry = managed.claim(this)
    if (entry eq null) {
      throw new IllegalStateException(s"${displayName}: the close ran on a thread that may not take the permit")
    }
    try {
      releaseOwned()
    } finally {
      // A dispatched runtime's bound thread could otherwise still enter synchronously.
      managed.retire()
      managed.exit(entry)
    }
  }

  /** The cleanup itself, under the permit: release what this runtime owns, each part even when an earlier one failed. */
  private def releaseOwned(): Unit = {
    val closing = JsArray[TaskScope]()
    scopes.forEach(closing.push(_))
    closing.forEach(scope => releasing(scope.close()))
    releasing(timers.cancelAll())
    val sources = JsArray[SourceEndpoint[?]]()
    bridgeSources.forEach(sources.push(_))
    sources.forEach(endpoint => releasing(endpoint.detach()))
    val targets = JsArray[TargetEndpoint[?]]()
    bridgeTargets.forEach(targets.push(_))
    targets.forEach(endpoint => releasing(endpoint.closeInside()))
  }

  /** Run one cleanup step; a failure is reported and the cleanup goes on without attempting rollback. */
  private def releasing(step: => Unit): Unit = {
    try {
      step
    } catch {
      case NonFatal(err) => sendUnhandledError(err)
    }
  }

  /** Report the admission cutoff of a close to the trace, when one is installed. */
  private[airstream] def traceAdmissionClosed(): Unit = {
    if (sink ne null) {
      sink.admissionClosed(this)
    }
  }

  /** Report a finished close to the trace, when one is installed. */
  private[airstream] def traceClosed(): Unit = {
    if (sink ne null) {
      sink.runtimeClosed(this)
    }
  }

  /** A new [[TaskScope]] whose results are delivered into this runtime; the runtime's close closes it. */
  def taskScope(): TaskScope = run {
    val scope = new TaskScope(this)
    scopes.push(scope)
    scope
  }

  /** The open task scopes of this runtime, for its close. Touched only under the permit. */
  private val scopes: JsArray[TaskScope] = JsArray()

  /** Forget a scope that closed by itself. Under the permit. */
  private[airstream] def forgetScope(scope: TaskScope): Unit = {
    val index = scopes.indexOf(scope)
    if (index >= 0) {
      scopes.splice(index, deleteCount = 1)
    }
  }

  /** The bridges whose source endpoint is attached here, and those whose target endpoint is open here, for the close.
    * Touched only under the permit.
    */
  private val bridgeSources: JsArray[SourceEndpoint[?]] = JsArray()

  private val bridgeTargets: JsArray[TargetEndpoint[?]] = JsArray()

  private[airstream] def holdBridgeSource(endpoint: SourceEndpoint[?]): Unit = bridgeSources.push(endpoint)

  private[airstream] def holdBridgeTarget(endpoint: TargetEndpoint[?]): Unit = bridgeTargets.push(endpoint)

  private[airstream] def forgetBridgeSource(endpoint: SourceEndpoint[?]): Unit = {
    val index = bridgeSources.indexOf(endpoint)
    if (index >= 0) {
      bridgeSources.splice(index, deleteCount = 1)
    }
  }

  private[airstream] def forgetBridgeTarget(endpoint: TargetEndpoint[?]): Unit = {
    val index = bridgeTargets.indexOf(endpoint)
    if (index >= 0) {
      bridgeTargets.splice(index, deleteCount = 1)
    }
  }

  /** How many bridge endpoints this runtime holds, source and target ones. Under the permit. */
  private[airstream] def bridgeEndpointCount: Int = bridgeSources.length + bridgeTargets.length

  /** True once a close cut admission off or finished: a refused delivery is then suppressed on purpose, not an error. */
  private[airstream] def isShutDown: Boolean = {
    val current = state
    (current eq RuntimeState.Closing) || (current eq RuntimeState.Closed)
  }

  /** Ids of the managed tasks launched in this runtime's scopes; 64-bit, never reused. */
  private val taskIds: AtomicCounter = new AtomicCounter

  private[airstream] def nextTaskId(): Long = taskIds.incrementAndGet()

  /** The transaction stack and child queues of this runtime. Touched only under the permit. */
  private[airstream] val transactions: TransactionEngine = new TransactionEngine(this)

  /** The shared-start batching state of this runtime. Touched only under the permit. */
  private[airstream] val sharedStart: SharedStart = new SharedStart(this)

  /** This runtime's delayed callbacks. On JVM and Native they wait in a queue the host pumps with `timers.runDue`; on
    * Scala.js the event loop fires them. Either way a due callback runs inside this runtime.
    */
  val timers: RuntimeTimers = new RuntimeTimers(this)

  /** How many nested transactions this runtime schedules before it reports `TransactionDepthExceeded` instead; -1
    * disables the check. See `Transaction.maxDepth` for the rationale.
    */
  var maxDepth: Int = 1000

  // The migration feature flags and the strict-signal reporting flag, per runtime. The process-wide facades
  // (`FeatureFlags`, `AirstreamError.PRE_V18_STRICT_SIGNAL_ERROR_REPORTING`) read and write the default runtime's copy; a
  // confined runtime takes the values in force when it is created and is configured through its own fields afterwards.

  /** See `FeatureFlags.V18_EVENTBUS_ISSTARTED_FIX_155`. */
  private[airstream] var eventBusIsStartedFix155: Boolean = true

  /** See `FeatureFlags.V18_TRX_ONSTART_ADD_FAIL_EARLY`. */
  private[airstream] var trxOnStartAddFailEarly: Boolean = true

  /** See `FeatureFlags.V18_TRX_ONSTART_FIX_144`. */
  private[airstream] var trxOnStartFix144: Boolean = true

  /** See `FeatureFlags.V18_IMMEDIATE_DYNSUB_REMOVAL_FIX_145`. */
  private[airstream] var immediateDynSubRemovalFix145: Boolean = true

  /** See `AirstreamError.PRE_V18_STRICT_SIGNAL_ERROR_REPORTING`. */
  private[airstream] var strictSignalErrorReporting: Boolean = false

  /** Where this runtime's diagnostics go (level, message, value); see `DevTools.setSink`. */
  private[airstream] var diagnostics: (DiagnosticLevel, String, Any) => Unit = DiagnosticSinks.default

  /** Copy the configuration of `source` into this runtime: the snapshot a new runtime takes of the default runtime. */
  private def configureFrom(source: Runtime): Unit = {
    maxDepth = source.maxDepth
    eventBusIsStartedFix155 = source.eventBusIsStartedFix155
    trxOnStartAddFailEarly = source.trxOnStartAddFailEarly
    trxOnStartFix144 = source.trxOnStartFix144
    immediateDynSubRemovalFix145 = source.immediateDynSubRemovalFix145
    strictSignalErrorReporting = source.strictSignalErrorReporting
    diagnostics = source.diagnostics
  }

  private var lastUpdateId: Int = 0

  /** Allocate the next signal update id. Ids are compared only between signals of one runtime (a child remembers the
    * id of its parent's last update), so each runtime counts on its own and another runtime's traffic never advances
    * them; `0` stays the "initial value" marker and the counter wraps to `1`.
    */
  private[airstream] def nextUpdateId(): Int = {
    // Note: Int.MaxValue is lower than JS native Number.MAX_SAFE_INTEGER
    if (lastUpdateId == Int.MaxValue) {
      lastUpdateId = 1
    } else {
      lastUpdateId += 1
    }
    lastUpdateId
  }

  /** Let the next update id follow `lastId`: the test-only counter start places wraparound inside a scenario. */
  private[airstream] def continueUpdateIdsAfter(lastId: Int): Unit = run {
    lastUpdateId = lastId
  }

  /** Add a callback for errors this runtime's graphs leave unhandled. The default runtime starts with
    * `AirstreamError.consoleErrorCallback`; a confined runtime starts with a copy of the default runtime's callbacks.
    */
  def registerUnhandledErrorCallback(fn: Throwable => Unit): Unit = {
    unhandledErrorCallbacks.push(fn)
  }

  /** Remove a callback added with [[registerUnhandledErrorCallback]]; throws when it is not registered here. */
  def unregisterUnhandledErrorCallback(fn: Throwable => Unit): Unit = {
    val ix = unhandledErrorCallbacks.indexOf(fn)
    if (ix >= 0) {
      unhandledErrorCallbacks.splice(ix, deleteCount = 1)
    } else {
      throw new Exception("This function is not currently registered as unhandled error callback. Make sure you're not accidentally creating a new function value when calling this.")
    }
  }

  /** Report an error nothing in this runtime's graph handled to its callbacks. Unhandled error reporting is the last
    * line of defence for errors that would otherwise disappear; see `AirstreamError.registerUnhandledErrorCallback`.
    */
  // @TODO[API,Integrity] How should we report errors here? Must make sure to not induce an infinite loop. Throw an error in a setTimeout?
  def sendUnhandledError(err: Throwable): Unit = {
    unhandledErrorCallbacks.forEach { fn =>
      try {
        fn(err)
      } catch {
        case err: Throwable if fn == AirstreamError.unsafeRethrowErrorCallback =>
          // Note: this does not let other error callbacks execute
          throw err
        case err: Throwable =>
          DevTools.warn("Error processing an unhandled error callback:")
          timers.setTimeout(0)(throw err)
      }
    }
  }

  /** Check that the calling thread may access this runtime's graph right now, without entering: the check a derived
    * observable makes when it is constructed, since constructing it reads its parents' state.
    *
    * @throws CrossRuntimeAccess when the calling thread is inside another runtime's turn
    * @throws ForeignThreadEntry when the permit would refuse the calling thread
    */
  private[airstream] def requireAccess(): Unit = {
    if (!gate.isHeldByCurrentThread) {
      val refusal = gate.accessRefusal(this)
      if (refusal ne null) {
        throw refusal
      }
    }
  }

  /** True while some thread is inside `run` on this runtime. */
  def isEntered: Boolean = gate.isHeld

  /** The thread that is inside this runtime right now, when there is one. */
  def holderName: Option[String] = gate.holderName

  /** The thread this runtime is bound to, when it is bound. */
  def boundThreadName: Option[String] = gate.boundThreadName

  override protected def defaultDisplayName: String = {
    if (this eq Runtime.default) "Runtime.default" else s"Runtime@${hashCode()}"
  }
}

object Runtime {

  /** The default runtime's gate, which also knows whether the program has other runtimes (see [[RuntimeContexts]]). */
  private val defaultGate: DefaultGate = new DefaultGate

  /** The runtime every observable belongs to unless it was created inside another runtime's `run`.
    *
    * It carries the state and configuration that the process-wide facades (`Transaction.maxDepth`, `FeatureFlags`,
    * `AirstreamError.registerUnhandledErrorCallback`, `Timers`) read and write, so code written before runtimes existed
    * keeps its behaviour.
    */
  val default: Runtime = new Runtime(
    _ => defaultGate,
    initialUnhandledErrorCallbacks = JsArray(AirstreamError.consoleErrorCallback)
  )

  /** The `activeCommand` value of a thread outside every command. */
  private[airstream] val NoCommand: Long = -1L

  /** How many admitted commands a scheduled runtime's mailbox holds before it rejects the next one. */
  val DefaultCapacity: Int = 4096

  /** How many complete turns one drain runs before it yields the executor to other runtimes. */
  val DefaultTurnsPerDrain: Int = 32

  /** A new runtime bound to the calling thread: only that thread may enter it, every other thread is rejected before any
    * graph access. On Scala.js the binding is moot and the runtime is simply a separate one.
    */
  def confined(): Runtime = create(_ => new ManagedGate(RuntimePermit.boundToCurrentThread(), mailboxFor = null))

  /** A new runtime whose turns run on `executor`, one at a time, with the default capacity and drain budget.
    *
    * Commands reach it through `submit` from any thread; `run` is rejected outside its turns. The executor may be a pool
    * shared with other runtimes: each drain holds one of its threads for at most `DefaultTurnsPerDrain` turns, and the
    * runtime's identity never depends on which thread ran a turn.
    */
  def scheduled(executor: ExecutionContext): Runtime = scheduled(executor, DefaultCapacity, DefaultTurnsPerDrain)

  /** A new runtime whose turns run on `executor`, one at a time; see the one-argument form.
    *
    * @param capacity how many admitted commands the mailbox holds; the next `submit` is rejected before any effect
    * @param turnsPerDrain how many complete turns one drain runs before it yields the executor; never splits a turn
    */
  def scheduled(executor: ExecutionContext, capacity: Int, turnsPerDrain: Int): Runtime = {
    require(capacity > 0, s"capacity must be positive, got $capacity")
    require(turnsPerDrain > 0, s"turnsPerDrain must be positive, got $turnsPerDrain")
    create(runtime =>
      new ManagedGate(RuntimePermit.scheduled(), gate => new Mailbox(runtime, gate, executor, capacity, turnsPerDrain))
    )
  }

  /** A new runtime for a UI thread: a scheduled runtime whose dispatcher always runs on the calling thread.
    *
    * It is both: the calling thread, the one the dispatcher runs on, may `run` synchronously as on a confined runtime,
    * and every other thread reaches the graph through `submit`, whose turns the dispatcher runs on that same thread. A
    * dispatcher that runs a drain on another thread is a misconfiguration: that drain fails the runtime instead of
    * touching the graph from a foreign thread. Create it on the UI thread.
    */
  def dispatched(dispatcher: ExecutionContext): Runtime =
    create(runtime =>
      new ManagedGate(
        RuntimePermit.boundToCurrentThread(),
        gate => new Mailbox(runtime, gate, dispatcher, DefaultCapacity, DefaultTurnsPerDrain)
      )
    )

  /** A runtime besides the default one, with the gate `gateFor` builds. The program starts tracking the other runtimes' turns
    * before the runtime exists, so no turn of it can go unseen.
    */
  private def create(gateFor: Runtime => RuntimeGate): Runtime = {
    defaultGate.admitOtherRuntimes()
    val runtime = new Runtime(gateFor, initialUnhandledErrorCallbacks = copyOf(default.unhandledErrorCallbacks))
    runtime.configureFrom(default)
    runtime
  }

  /** The runtime whose turn the calling thread is inside, or null: another runtime's, which the per-thread slot shows, or the
    * default runtime's, which its permit shows.
    */
  private[airstream] def activeTurn: Runtime = {
    val other = defaultGate.contexts.activeOther
    if (other ne null) other
    else if (defaultGate.isHeldByCurrentThread) default
    else null
  }

  /** True when the calling thread is inside a turn of the default runtime. */
  private[airstream] def isInDefaultTurn: Boolean = defaultGate.isHeldByCurrentThread

  /** Reject a blocking wait for `receipt` from inside any runtime's turn, before it blocks. */
  private[airstream] def requireOutsideTurns(receipt: Receipt): Unit = {
    val active = activeTurn
    if (active ne null) {
      throw WaitInsideTurn(active, receipt)
    }
  }

  private def copyOf(callbacks: JsArray[Throwable => Unit]): JsArray[Throwable => Unit] = {
    val copy = JsArray[Throwable => Unit]()
    callbacks.forEach(copy.push(_))
    copy
  }

  /** The runtime a derived observable with this one parent belongs to, checked for access by the constructing thread. */
  private[airstream] def inheritedFrom(parent: Observable[?]): Runtime = {
    val inherited = parent.runtime
    inherited.requireAccess()
    inherited
  }

  /** The one runtime these parents share, for a derived observable over all of them; the current runtime when there
    * are no parents. Checked for access by the constructing thread.
    *
    * @throws CrossRuntimeEdge when the parents belong to different runtimes; checked before the observable connects
    */
  private[airstream] def sharedBy(parents: JsArray[? <: Observable[?]]): Runtime = {
    if (parents.length == 0) {
      current
    } else {
      val shared = parents(0).runtime
      parents.forEach(parent => requireSame(shared, parent.runtime))
      shared.requireAccess()
      shared
    }
  }

  /** The one runtime `first` and `others` share, for a sampling observable. Checked for access by the constructing thread. */
  private[airstream] def sharedBy(first: Observable[?], others: JsArray[? <: Observable[?]]): Runtime = {
    val shared = first.runtime
    others.forEach(other => requireSame(shared, other.runtime))
    shared.requireAccess()
    shared
  }

  /** The one runtime a batch over these participants runs in; the current runtime when the batch is empty.
    *
    * @throws CrossRuntimeEdge when the participants belong to different runtimes; checked before any write
    */
  private[airstream] def sharedBy(runtimes: Seq[Runtime]): Runtime = {
    runtimes.headOption.fold(current) { shared =>
      runtimes.foreach(runtime => requireSame(shared, runtime))
      shared
    }
  }

  /** Reject an edge between `expected` and `actual` unless they are the same runtime. */
  @inline private[airstream] def requireSame(expected: Runtime, actual: Runtime): Unit = {
    defaultGate.contexts.requireSame(expected, actual)
  }

  /** The runtime whose turn the current thread is inside, or the default runtime when it is inside none. */
  def current: Runtime = {
    val active = defaultGate.contexts.activeOther
    if (active eq null) default else active
  }
}
