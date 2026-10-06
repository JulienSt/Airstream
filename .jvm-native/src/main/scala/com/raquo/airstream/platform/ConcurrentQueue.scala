package com.raquo.airstream.platform

import java.util.concurrent.ConcurrentLinkedQueue

/** A first-in first-out queue producers on any thread append to and one drainer takes from.
  *
  * This is the mailbox's publication channel: an element offered here is
  * visible, with everything written before it, to the thread that polls it. Appending never blocks and never waits for
  * the drainer, which is what lets a producer be turned away or accepted without ever holding the runtime up.
  */
private[airstream] final class ConcurrentQueue[A <: AnyRef] {

  private val queue: ConcurrentLinkedQueue[A] = new ConcurrentLinkedQueue[A]()

  /** Append `element` at the tail. */
  def offer(element: A): Unit = {
    queue.offer(element)
    ()
  }

  /** Remove and return the head, or null when the queue is empty. */
  def poll(): A = queue.poll()

  /** True when nothing is queued at the moment of the check. */
  def isEmpty: Boolean = queue.isEmpty
}
