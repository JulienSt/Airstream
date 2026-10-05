package com.raquo.airstream.platform

/** One boolean with a compare-and-set, on a platform with a single thread: a plain field. */
final class AtomicFlag {

  private var value: Boolean = false

  /** The current value. */
  def get: Boolean = value

  /** Replace the value. */
  def set(next: Boolean): Unit = {
    value = next
  }

  /** Replace the value when it still is `expected`. */
  def compareAndSet(expected: Boolean, next: Boolean): Boolean = {
    if (value == expected) {
      value = next
      true
    } else {
      false
    }
  }
}
