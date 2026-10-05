package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.{CrossRuntimeAccess, ForeignThreadEntry}
import com.raquo.airstream.platform.{RuntimePermit, ThreadSlot}

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
final class Runtime private (permit: RuntimePermit, isDefault: Boolean) extends Named {

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

  /** Report an error nothing in the graph handled. Routed through the process-wide callbacks for now. */
  def sendUnhandledError(err: Throwable): Unit = AirstreamError.sendUnhandledError(err)

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
  val default: Runtime = new Runtime(RuntimePermit.floating(), isDefault = true)

  /** A new runtime bound to the calling thread: only that thread may enter it, every other thread is rejected before any
    * graph access. On Scala.js the binding is moot and the runtime is simply a separate one.
    */
  def confined(): Runtime = new Runtime(RuntimePermit.boundToCurrentThread(), isDefault = false)

  /** The runtime whose turn the current thread is inside, or the default runtime when it is inside none. */
  def current: Runtime = {
    val active = context.get
    if (active eq null) default else active
  }
}
