package com.raquo.airstream.platform

/** One reference with a compare-and-set, on a platform with a single thread: a plain field.
  *
  * The class exists so the shared scheduler is written once against the surface the threaded platforms provide; here
  * the compare-and-set is an ordinary check-then-write, which no other thread can interleave.
  */
final class AtomicCell[A <: AnyRef](initial: A) {

  private var value: A = initial

  /** The current value. */
  def get: A = value

  /** Replace the value. */
  def set(next: A): Unit = {
    value = next
  }

  /** Replace the value when it still is `expected`. */
  def compareAndSet(expected: A, next: A): Boolean = {
    if (value eq expected) {
      value = next
      true
    } else {
      false
    }
  }
}
