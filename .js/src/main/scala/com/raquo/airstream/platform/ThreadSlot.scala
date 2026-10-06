package com.raquo.airstream.platform

/** One value per thread, on a platform with a single thread: a plain slot.
  *
  * JavaScript has one realm and no preemption, so "the runtime the current thread has entered" is simply the runtime
  * that was entered last and not yet exited. The class exists so the shared core is written once against the same
  * surface the threaded platforms provide, including the cell an entry checks, installs and clears in one lookup.
  */
final private[airstream] class ThreadSlot[A <: AnyRef] {

  private val only: ThreadSlot.Cell[A] = new ThreadSlot.Cell[A]

  /** The one cell. */
  def cell: ThreadSlot.Cell[A] = only

  /** The installed value, or null when nothing is installed. */
  def get: A = only.value

  /** Install `next`; null clears the slot. */
  def set(next: A): Unit = {
    only.value = next
  }
}

private[airstream] object ThreadSlot {

  /** The value of the one thread. */
  final class Cell[A <: AnyRef] {
    var value: A = null.asInstanceOf[A]
  }
}
