package com.raquo.airstream.platform

/** One value per thread, on a platform that has threads.
  *
  * A runtime installs itself here while it executes a turn, so code that creates an observable or a transaction can find
  * the runtime it belongs to without being handed it as an argument. Thread-local storage is the natural carrier: the
  * value is meaningful only on the thread that entered, and must never leak to a worker that happens to run next.
  *
  * The slot is cleared with `remove` rather than set to null so that a pool thread does not keep an empty entry alive.
  */
final class ThreadSlot[A <: AnyRef] {

  private val slot: ThreadLocal[A] = new ThreadLocal[A]

  /** The value installed on the current thread, or null when nothing is installed. */
  def get: A = slot.get()

  /** Install `value` on the current thread; null clears the slot. */
  def set(value: A): Unit = {
    if (value eq null) {
      slot.remove()
    } else {
      slot.set(value)
    }
  }
}
