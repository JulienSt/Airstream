package com.raquo.airstream.platform

import java.util.concurrent.atomic.AtomicReference

/** The default runtime's permit on a platform with threads: whichever thread enters first holds it until that entry returns,
  * and a second thread arriving meanwhile is turned away without waiting.
  *
  * The holder is taken with a compare-and-set wherever a second thread can exist, because the threads that take it race. A
  * Scala Native program linked without multithreading has one thread, so there the branch on `Multithreading.enabled`, a
  * link-time constant, leaves a check and a release store in its place and the linker drops the compare-and-set. The
  * JVM always has threads and always keeps it.
  */
final private[airstream] class DefaultPermit {

  private val holder: AtomicReference[Thread] = new AtomicReference[Thread](null)

  /** True when the calling thread holds the permit, which is what makes a nested entry free. */
  def isHeldByCurrentThread: Boolean = {
    if (Multithreading.enabled) {
      holder.get() eq Thread.currentThread()
    } else {
      holder.get() ne null
    }
  }

  /** True while any thread holds the permit. */
  def isHeld: Boolean = holder.get() ne null

  /** Asked only by a thread that does not hold the permit: true when nobody holds it. */
  def mayAcquire: Boolean = holder.get() eq null

  /** Take the permit for the calling thread, which does not hold it, and return `entry`; null when another thread holds it. */
  def acquireFor[E >: Null <: AnyRef](entry: E): E = if (acquire()) entry else null

  private def acquire(): Boolean = {
    val current = Thread.currentThread()
    if (Multithreading.enabled) {
      holder.compareAndSet(null, current)
    } else if (holder.get() eq null) {
      holder.lazySet(current)
      true
    } else {
      false
    }
  }

  /** Give the permit back. Only the holder calls this, so a release store suffices: everything the holder did is visible to the
    * next thread whose compare-and-set takes the permit (handoff section 15.5).
    */
  def release(): Unit = holder.lazySet(null)

  /** The name of the holding thread, while there is one. */
  def holderName: Option[String] = Option(holder.get()).map(_.getName)
}
