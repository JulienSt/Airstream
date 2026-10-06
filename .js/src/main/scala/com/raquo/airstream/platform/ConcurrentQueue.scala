package com.raquo.airstream.platform

import scala.collection.mutable

/** A first-in first-out queue, on a platform with a single thread: a plain deque.
  *
  * Producers and the drainer are the same thread here, interleaved only by the event loop between tasks, so no
  * publication is needed; the class keeps the shared scheduler written once.
  */
private[airstream] final class ConcurrentQueue[A <: AnyRef] {

  private val queue: mutable.ArrayDeque[A] = mutable.ArrayDeque.empty

  /** Append `element` at the tail. */
  def offer(element: A): Unit = {
    queue.append(element)
    ()
  }

  /** Remove and return the head, or null when the queue is empty. */
  def poll(): A = {
    if (queue.isEmpty) {
      null.asInstanceOf[A]
    } else {
      queue.removeHead()
    }
  }

  /** True when nothing is queued. */
  def isEmpty: Boolean = queue.isEmpty
}
