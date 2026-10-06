package com.raquo.airstream.platform

/** One value per thread, on a platform that has threads.
  *
  * A runtime installs itself here while it executes a turn, so code that creates an observable or a transaction can find
  * the runtime it belongs to without being handed it as an argument. Thread-local storage is the natural carrier: the
  * value is meaningful only on the thread that entered, and must never leak to a worker that happens to run next.
  *
  * The slot is cleared by setting it to null, not with `remove`: a removed entry is allocated again by the next `set`,
  * which made every root entry of a runtime allocate (`RootEntryAllocationSpec`). What a thread keeps is one
  * entry holding null for this one slot, which no runtime or value is reachable from.
  */
private[airstream] final class ThreadSlot[A <: AnyRef] {

  private val slot: ThreadLocal[A] = new ThreadLocal[A]

  /** The value installed on the current thread, or null when nothing is installed. */
  def get: A = slot.get()

  /** Install `value` on the current thread; null clears the slot. */
  def set(value: A): Unit = slot.set(value)
}
