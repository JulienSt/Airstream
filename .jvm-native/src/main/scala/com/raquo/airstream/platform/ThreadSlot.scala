package com.raquo.airstream.platform

/** One value per thread, on a platform that has threads.
  *
  * A runtime installs itself here while it executes a turn, so code that creates an observable or a transaction can find
  * the runtime it belongs to without being handed it as an argument. Thread-local storage is the natural carrier: the
  * value is meaningful only on the thread that entered, and must never leak to a worker that happens to run next.
  *
  * Each thread gets one mutable cell, created on its first access and kept: a root entry looks the cell up once and then
  * installs and clears the value with plain writes to it, instead of a thread-local lookup to check, another to install and
  * a third to clear. Clearing never removes the cell, so no entry allocates (`RootEntryAllocationSpec`). What a
  * thread keeps is that one cell, holding null between entries, from which no runtime or value is reachable.
  */
final private[airstream] class ThreadSlot[A <: AnyRef] {

  private val slot: ThreadLocal[ThreadSlot.Cell[A]] = ThreadLocal.withInitial(() => new ThreadSlot.Cell[A])

  /** The calling thread's cell, for an entry that checks, installs and clears in one lookup. */
  def cell: ThreadSlot.Cell[A] = slot.get()

  /** The value installed on the current thread, or null when nothing is installed. */
  def get: A = slot.get().value

  /** Install `value` on the current thread; null clears the slot. */
  def set(value: A): Unit = slot.get().value = value
}

private[airstream] object ThreadSlot {

  /** The calling thread's value; read and written only by that thread. */
  final class Cell[A <: AnyRef] {
    var value: A = null.asInstanceOf[A]
  }
}
