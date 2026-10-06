package com.raquo.airstream.platform

import java.util.concurrent.atomic.AtomicLong

/** One 64-bit counter several threads advance: command ids and the mailbox's reserved capacity.
  *
  * Ids are 64-bit so a runtime never reuses one within any realistic lifetime; the
  * capacity count is advanced by compare-and-set so admission at the limit is decided atomically.
  */
private[airstream] final class AtomicCounter {

  private val value: AtomicLong = new AtomicLong(0L)

  /** The current value. */
  def get: Long = value.get()

  /** Advance by one and return the new value. */
  def incrementAndGet(): Long = value.incrementAndGet()

  /** Decrease by one and return the new value. */
  def decrementAndGet(): Long = value.decrementAndGet()

  /** Replace the value when it still is `expected`; false when another thread changed it first. */
  def compareAndSet(expected: Long, next: Long): Boolean = value.compareAndSet(expected, next)
}
