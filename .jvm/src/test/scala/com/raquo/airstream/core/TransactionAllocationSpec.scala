package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import java.lang.management.ManagementFactory

/** What one root event allocates on the direct path, end to end. The ceilings preserve the measured pre-runtime
  * allocation costs for EventBus (88 bytes), Var (160 bytes), a map chain (800 bytes), and a diamond (824 bytes): a runtime that only adds one entry per root write must
  * not allocate more per event than the code before it did. What a root event inherently allocates is the transaction, the
  * closure it runs, the `Try` a Var stores and the boxed `Int` of these probes; everything else on the path must be
  * allocation-free by construction rather than by the JIT's escape analysis, which gives up once a shared call site turns
  * megamorphic, as it does in this test JVM after the other suites have run.
  *
  * Measured with HotSpot's per-thread allocation counter after a warm-up long enough for the JIT to compile the path; JVM
  * only, because Scala Native has no such counter.
  */
class TransactionAllocationSpec extends UnitSpec {

  private val threads = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  private val eventsPerIteration = 200000

  /** The median bytes per event over `measured` iterations, after `warmup` untimed ones, each emitting
    * `eventsPerIteration` distinct values exactly as the benchmark does.
    */
  private def bytesPerEvent(emit: Int => Unit, warmup: Int = 20, measured: Int = 5): Double = {
    def iteration(): Double = {
      val before = threads.getCurrentThreadAllocatedBytes
      var value = 0
      while (value < eventsPerIteration) {
        emit(value)
        value += 1
      }
      (threads.getCurrentThreadAllocatedBytes - before).toDouble / eventsPerIteration
    }
    (1 to warmup).foreach(_ => iteration())
    val samples = (1 to measured).map(_ => iteration()).sorted
    samples(samples.length / 2)
  }

  private def withinCeiling(probe: String, ceiling: Int)(setup: (ManualOwner, Array[Long]) => Int => Unit): Unit = {
    val owner = new ManualOwner
    val sink = Array(0L)
    val bytes = bytesPerEvent(setup(owner, sink))
    owner.killSubscriptions()
    info(s"$probe: $bytes bytes per event")
    withClue(s"$probe: $bytes bytes per event against a ceiling of $ceiling: ") {
      (bytes <= ceiling) shouldBe true
    }
  }

  it("an EventBus emission to one observer allocates no more than the base did (88 bytes)") {
    withinCeiling("eventbus-to-observer", ceiling = 88) { (owner, sink) =>
      val bus = new EventBus[Int]
      bus.events.foreach(value => sink(0) += value)(owner)
      value => bus.emit(value)
    }
  }

  it("a Var write to one observer allocates no more than the base did (160 bytes)") {
    withinCeiling("var-to-observer", ceiling = 160) { (owner, sink) =>
      val source = Var(0)
      source.signal.foreach(value => sink(0) += value)(owner)
      value => source.set(value)
    }
  }

  it("a Var write through a chain of ten maps allocates no more than the base did (800 bytes)") {
    withinCeiling("map-chain-10", ceiling = 800) { (owner, sink) =>
      val source = Var(0)
      val chain = (1 to 10).foldLeft(source.signal: Signal[Int])((current, _) => current.map(_ + 1))
      chain.foreach(value => sink(0) += value)(owner)
      value => source.set(value)
    }
  }

  it("a Var write through a diamond allocates no more than the base did (824 bytes)") {
    withinCeiling("diamond", ceiling = 824) { (owner, sink) =>
      val source = Var(0)
      val left = source.signal.map(_ + 1)
      val right = source.signal.map(_ * 2)
      left.combineWith(right).foreach { case (l, r) => sink(0) += l + r }(owner)
      value => source.set(value)
    }
  }
}
