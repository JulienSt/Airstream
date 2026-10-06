package com.raquo.airstream.platform

import java.util.concurrent.atomic.AtomicReference

/** One reference written and read by several threads, with a compare-and-set.
  *
  * The scheduler's admission state and its drain claim are decided by compare-and-set rather than by a lock: a producer
  * that loses the race retries or is turned away, it never waits, and a drainer's claim is one
  * atomic step the M1 model can name. Writes publish to the next reader through the atomic.
  */
final private[airstream] class AtomicCell[A <: AnyRef](initial: A) {

  private val ref: AtomicReference[A] = new AtomicReference[A](initial)

  /** The current value. */
  def get: A = ref.get()

  /** Replace the value. */
  def set(value: A): Unit = ref.set(value)

  /** Replace the value when it still is `expected`; false when another thread changed it first. */
  def compareAndSet(expected: A, next: A): Boolean = ref.compareAndSet(expected, next)

  /** Replace the value and return the one it replaced, in one atomic step: exactly one caller sees each value leave. */
  def getAndSet(next: A): A = ref.getAndSet(next)
}
