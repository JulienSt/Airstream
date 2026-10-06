package com.raquo.airstream.platform

import java.util.concurrent.atomic.AtomicBoolean

/** One boolean several threads claim and release: the drain claim of a scheduled runtime's mailbox. */
final private[airstream] class AtomicFlag {

  private val flag: AtomicBoolean = new AtomicBoolean(false)

  /** The current value. */
  def get: Boolean = flag.get()

  /** Replace the value. */
  def set(value: Boolean): Unit = flag.set(value)

  /** Replace the value when it still is `expected`; false when another thread changed it first. */
  def compareAndSet(expected: Boolean, next: Boolean): Boolean = flag.compareAndSet(expected, next)
}
