package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.{CrossRuntimeAccess, ForeignThreadEntry}
import com.raquo.airstream.platform.{DevTools, JsArray, RuntimePermit, ThreadSlot, Timers}

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

  /** How many nested transactions this runtime schedules before it reports `TransactionDepthExceeded` instead; -1
    * disables the check. See `Transaction.maxDepth` for the rationale.
    */
  var maxDepth: Int = 1000

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
          Timers.setTimeout(0)(throw err)
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
  def confined(): Runtime = new Runtime(
    RuntimePermit.boundToCurrentThread(),
    isDefault = false,
    initialUnhandledErrorCallbacks = copyOf(default.unhandledErrorCallbacks)
  )

  private def copyOf(callbacks: JsArray[Throwable => Unit]): JsArray[Throwable => Unit] = {
    val copy = JsArray[Throwable => Unit]()
    callbacks.forEach(copy.push(_))
    copy
  }

  /** The runtime a derived observable with these parents belongs to: the parents' runtime, or the current one when
    * there are no parents.
    */
  private[airstream] def of(parents: JsArray[? <: Observable[?]]): Runtime = {
    if (parents.length == 0) current else parents(0).runtime
  }

  /** The runtime a batch over these participants belongs to: theirs, or the current one when the batch is empty. */
  private[airstream] def of(runtimes: Seq[Runtime]): Runtime = runtimes.headOption.getOrElse(current)

  /** The runtime whose turn the current thread is inside, or the default runtime when it is inside none. */
  def current: Runtime = {
    val active = context.get
    if (active eq null) default else active
  }
}
