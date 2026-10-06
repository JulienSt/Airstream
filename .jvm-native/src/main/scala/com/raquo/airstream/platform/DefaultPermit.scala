package com.raquo.airstream.platform

import java.util.concurrent.atomic.AtomicReference

/** The default runtime's permit on a platform with threads: whichever thread enters first holds it until that entry returns,
  * and a second thread arriving meanwhile is turned away without waiting. The holder is taken with a compare-and-set,
  * because the threads that take it race.
  */
final private[airstream] class DefaultPermit {

  private val holder: AtomicReference[Thread] = new AtomicReference[Thread](null)

  /** True when the calling thread holds the permit, which is what makes a nested entry free. */
  def isHeldByCurrentThread: Boolean = holder.get() eq Thread.currentThread()

  /** True while any thread holds the permit. */
  def isHeld: Boolean = holder.get() ne null

  /** Asked only by a thread that does not hold the permit: true when nobody holds it. */
  def mayAcquire: Boolean = holder.get() eq null

  /** Take the permit for the calling thread, which does not hold it, and return `entry`; null when another thread holds it. */
  def acquireFor[E >: Null <: AnyRef](entry: E): E = if (acquire()) entry else null

  private def acquire(): Boolean = holder.compareAndSet(null, Thread.currentThread())

  /** Give the permit back. Only the holder calls this, so a release store suffices: everything the holder did is visible to the
    * next thread whose compare-and-set takes the permit (handoff section 15.5).
    */
  def release(): Unit = holder.lazySet(null)

  /** The name of the holding thread, while there is one. */
  def holderName: Option[String] = Option(holder.get()).map(_.getName)
}
