package com.raquo.airstream.util

import com.raquo.airstream.AllocationMeter.bytesPerCall
import com.raquo.airstream.UnitSpec

/** The priority queue a transaction files its pending observables in, and a merge stream its pending parent values, is
  * written per event: enqueueing, checking and dequeueing must allocate nothing once the queue has room. On
  * Scala.js the array operations are native; on the JVM they must not go through a varargs insert or a generic search that
  * builds a closure, a wrapper or an iterator per call.
  */
class JsPriorityQueueAllocationSpec extends UnitSpec {

  final private class Ranked(val rank: Int)

  it("enqueues, finds and dequeues without allocating once it has room") {
    val queue = new JsPriorityQueue[Ranked](_.rank)
    val low = new Ranked(1)
    val high = new Ranked(2)
    val found = Array(false)
    val bytes = bytesPerCall() { () =>
      queue.enqueue(high)
      queue.enqueue(low)
      found(0) = queue.contains(high)
      queue.dequeue()
      queue.dequeue()
    }
    withClue(s"$bytes bytes per enqueue, enqueue, contains, dequeue, dequeue: ") {
      (bytes < 1.0) shouldBe true
    }
    found(0) shouldBe true
  }
}
