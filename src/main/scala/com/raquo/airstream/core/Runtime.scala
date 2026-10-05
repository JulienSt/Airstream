package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.{CrossRuntimeAccess, CrossRuntimeEdge, ForeignThreadEntry}
import com.raquo.airstream.platform.{DevTools, DiagnosticLevel, DiagnosticSinks, JsArray, RuntimePermit, RuntimeTimers, ThreadSlot}

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
  */
final class Runtime private (
  permit: RuntimePermit,
  isDefault: Boolean,
  initialUnhandledErrorCallbacks: JsArray[Throwable => Unit]
) extends Named {

  private val unhandledErrorCallbacks: JsArray[Throwable => Unit] = initialUnhandledErrorCallbacks

  /** Execute `body` inside this runtime: on the permit-holding thread with this runtime as the current context.
    *
    * @throws ForeignThreadEntry when the calling thread may not take the permit
    * @throws CrossRuntimeAccess when the calling thread is inside another runtime's turn
    */
  def run[A](body: => A): A = {
    if (permit.isHeldByCurrentThread) {
      body
    } else {
      enter(body)
    }
  }

  private def enter[A](body: => A): A = {
    val active = Runtime.context.get
    if (active ne null) {
      // Checked before the permit so that a runtime never waits on, or takes, another runtime's permit from inside a turn.
      throw CrossRuntimeAccess(active = active, target = this)
    }
    if (!permit.acquire()) {
      throw ForeignThreadEntry(this)
    }
    Runtime.context.set(this)
    try {
      body
    } finally {
      Runtime.context.set(null)
      permit.release()
    }
  }

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
    if (!permit.isHeldByCurrentThread) {
      val active = Runtime.context.get
      if (active ne null) {
        throw CrossRuntimeAccess(active = active, target = this)
      }
      if (!permit.mayAcquire) {
        throw ForeignThreadEntry(this)
      }
    }
  }

  /** True while some thread is inside `run` on this runtime. */
  def isEntered: Boolean = permit.isHeld

  /** The thread that is inside this runtime right now, when there is one. */
  def holderName: Option[String] = permit.holderName

  /** The thread this runtime is bound to, when it is bound. */
  def boundThreadName: Option[String] = permit.boundThreadName

  override protected def defaultDisplayName: String = {
    if (isDefault) "Runtime.default" else s"Runtime@${hashCode()}"
  }
}

object Runtime {

  /** The runtime the current thread has entered, or null between entries. Platform storage: thread-local or a plain slot. */
  private val context: ThreadSlot[Runtime] = new ThreadSlot[Runtime]

  /** The runtime every observable belongs to unless it was created inside another runtime's `run`.
    *
    * It carries the state and configuration that the process-wide facades (`Transaction.maxDepth`, `FeatureFlags`,
    * `AirstreamError.registerUnhandledErrorCallback`, `Timers`) read and write, so code written before runtimes existed
    * keeps its behaviour.
    */
  val default: Runtime = new Runtime(
    RuntimePermit.floating(),
    isDefault = true,
    initialUnhandledErrorCallbacks = JsArray(AirstreamError.consoleErrorCallback)
  )

  /** A new runtime bound to the calling thread: only that thread may enter it, every other thread is rejected before any
    * graph access. On Scala.js the binding is moot and the runtime is simply a separate one.
    */
  def confined(): Runtime = {
    val runtime = new Runtime(
      RuntimePermit.boundToCurrentThread(),
      isDefault = false,
      initialUnhandledErrorCallbacks = copyOf(default.unhandledErrorCallbacks)
    )
    runtime.configureFrom(default)
    runtime
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
  private[airstream] def requireSame(expected: Runtime, actual: Runtime): Unit = {
    if (expected ne actual) {
      throw CrossRuntimeEdge(Seq(expected, actual))
    }
  }

  /** The runtime whose turn the current thread is inside, or the default runtime when it is inside none. */
  def current: Runtime = {
    val active = context.get
    if (active eq null) default else active
  }
}
