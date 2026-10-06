package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.fixtures.ThreadPool
import com.raquo.airstream.ownership.ManualOwner

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._

/** Bridges between runtimes on real threads: accepted source order, new target
  * transactions, the snapshot cut of a signal bridge, and the two closing acknowledgements with no endpoint left behind.
  */
class BridgeSpec extends UnitSpec {

  private val timeout = 5.seconds

  /** Two scheduled runtimes, each on a pool of its own, closed after `body`. */
  private def withRuntimes[A](body: (Runtime, Runtime, ThreadPool) => A): A = {
    val sourcePool = new ThreadPool("bridge-source", 1)
    val targetPool = new ThreadPool("bridge-target", 1)
    try body(Runtime.scheduled(sourcePool), Runtime.scheduled(targetPool), targetPool) finally {
      sourcePool.close()
      targetPool.close()
    }
  }

  /** Run `body` in a turn of `runtime` and return its value, from outside every runtime. */
  private def inTurn[A](runtime: Runtime)(body: => A): A = {
    val cell = new AtomicReference[Option[A]](None)
    runtime.submit(cell.set(Some(body))).awaitCompleted(timeout)
    cell.get.get
  }

  it("emits the source's events on the target bus in accepted order, each in a new transaction on the target's thread") {
    withRuntimes { (a, b, targetPool) =>
      val sourceBus = inTurn(a)(new EventBus[Int])
      val targetBus = inTurn(b)(new EventBus[Int])
      val seen = new AtomicReference(Vector.empty[(Int, String)])
      inTurn(b)(targetBus.events.foreach(value => seen.updateAndGet(_ :+ ((value, Thread.currentThread.getName))))(new ManualOwner))
      val bridge = inTurn(a)(EventBridge(sourceBus.events, targetBus))
      (1 to 20).foreach(value => a.submit(sourceBus.emit(value)))
      bridge.close().awaitCompleted(timeout)
      seen.get.map(_._1) shouldBe (1 to 20).toVector
      seen.get.map(_._2).toSet.subsetOf(targetPool.threadNames) shouldBe true
    }
  }
}
