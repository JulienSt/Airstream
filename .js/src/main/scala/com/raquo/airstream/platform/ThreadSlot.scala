package com.raquo.airstream.platform

/** One value per thread, on a platform with a single thread: a plain slot.
  *
  * JavaScript has one realm and no preemption, so "the runtime the current thread has entered" is simply the runtime
  * that was entered last and not yet exited. The class exists so the shared core is written once against the same
  * surface the threaded platforms provide.
  */
final class ThreadSlot[A <: AnyRef] {

  private var value: A = null.asInstanceOf[A]

  /** The installed value, or null when nothing is installed. */
  def get: A = value

  /** Install `next`; null clears the slot. */
  def set(next: A): Unit = {
    value = next
  }
}
