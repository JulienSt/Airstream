package com.raquo.airstream.platform

import java.util.concurrent.atomic.AtomicReference

/** The execution permit of one runtime, on a platform with real threads.
  *
  * At most one thread holds the permit at a time; that thread may touch the runtime's graph state, every other thread is
  * turned away before it reaches that state. The holder is a single atomic reference rather than a lock on purpose: a
  * rejected thread must never wait, and the ordinary synchronous path must
  * cost no more than one compare-and-set on the way in and one plain write on the way out.
  *
  * A permit is either bound to one thread for its whole life, which is what `Runtime.confined()` creates, or floating,
  * which is what the default runtime uses so that an application that touches Airstream from one thread at a time keeps
  * working whichever thread that is. A bound permit refuses every other thread even while it is free.
  *
  * A scheduled permit admits no synchronous entry at all: `acquire` always fails, so `run` from outside a turn is
  * rejected and the graph is reached through `submit`. Only the mailbox's drainer takes it, with `claim`, for one turn.
  *
  * Reentrancy needs no counter here: the runtime checks `isHeldByCurrentThread` first and runs a nested body directly,
  * so only the outermost entry acquires and releases.
  */
final private[airstream] class RuntimePermit private (bound: Thread, admitsEntry: Boolean) {

  private val holder: AtomicReference[Thread] = new AtomicReference[Thread](null)

  /** Set once by the runtime's close: from then on nothing takes the permit. */
  @volatile private var retired: Boolean = false

  /** True when the calling thread holds the permit, which is what makes a nested entry free. */
  def isHeldByCurrentThread: Boolean = holder.get() eq Thread.currentThread()

  /** True while any thread holds the permit. */
  def isHeld: Boolean = holder.get() ne null

  /** True when the calling thread is allowed to acquire the permit right now, without acquiring it. */
  def mayAcquire: Boolean = {
    val current = Thread.currentThread()
    !retired && admitsEntry && isBoundToOrUnbound(current) && {
      val currentHolder = holder.get()
      (currentHolder eq null) || (currentHolder eq current)
    }
  }

  /** Take the permit for the calling thread's synchronous entry; false when another thread holds it, the permit is bound
    * elsewhere, or the permit is scheduled and admits no synchronous entry.
    */
  def acquire(): Boolean = {
    val current = Thread.currentThread()
    !retired && admitsEntry && isBoundToOrUnbound(current) && holder.compareAndSet(null, current)
  }

  /** Take the permit for a drainer's turn on the calling thread; false when another thread holds it or the permit is
    * bound to another thread (a dispatcher that ran the drain elsewhere).
    */
  def claim(): Boolean = {
    val current = Thread.currentThread()
    !retired && isBoundToOrUnbound(current) && holder.compareAndSet(null, current)
  }

  /** Refuse every later `acquire` and `claim`; the holder, if any, keeps the permit until it releases it. */
  def retire(): Unit = {
    retired = true
  }

  /** Give the permit back. Only the holder calls this, so a release store suffices: everything the holder did before it is
    * visible to the next thread whose compare-and-set takes the permit, and to any thread that learns of the release
    * through a later synchronising action of the holder (a settled receipt). No full fence is needed on the hot path.
    */
  def release(): Unit = holder.lazySet(null)

  /** The name of the thread the permit is bound to, when it is bound. */
  def boundThreadName: Option[String] = Option(bound).map(_.getName)

  /** The name of the holding thread, while there is one. */
  def holderName: Option[String] = Option(holder.get()).map(_.getName)

  private def isBoundToOrUnbound(thread: Thread): Boolean = (bound eq null) || (bound eq thread)
}

private[airstream] object RuntimePermit {

  /** A permit any thread may take while it is free; one thread at a time. */
  def floating(): RuntimePermit = new RuntimePermit(bound = null, admitsEntry = true)

  /** A permit only the calling thread may ever take. */
  def boundToCurrentThread(): RuntimePermit = new RuntimePermit(bound = Thread.currentThread(), admitsEntry = true)

  /** A permit no synchronous entry may take; only a drainer claims it, on whichever thread the executor runs. */
  def scheduled(): RuntimePermit = new RuntimePermit(bound = null, admitsEntry = false)
}
