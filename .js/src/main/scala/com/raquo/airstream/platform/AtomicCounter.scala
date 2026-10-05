package com.raquo.airstream.platform

/** One 64-bit counter, on a platform with a single thread: a plain field. */
final class AtomicCounter {

  private var value: Long = 0L

  /** The current value. */
  def get: Long = value

  /** Advance by one and return the new value. */
  def incrementAndGet(): Long = {
    value += 1L
    value
  }

  /** Decrease by one and return the new value. */
  def decrementAndGet(): Long = {
    value -= 1L
    value
  }

  /** Replace the value when it still is `expected`. */
  def compareAndSet(expected: Long, next: Long): Boolean = {
    if (value == expected) {
      value = next
      true
    } else {
      false
    }
  }
}
