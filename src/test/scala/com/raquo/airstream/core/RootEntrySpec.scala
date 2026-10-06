package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import scala.collection.mutable

/** The cost of the direct path is paid per root call, once: a root write enters its runtime one time,
  * however many nodes it reaches, and the transaction it creates is scheduled inside that same entry. A second entry per
  * root write doubles the compare-and-set and the context install of the hot path (the stage-4 measurement found it).
  */
class RootEntrySpec extends UnitSpec {

  private def entriesOf(runtime: Runtime)(body: => Unit): Int = {
    var entries = 0
    runtime.traceTo(new RuntimeTrace {
      override def permitAcquired(r: Runtime): Unit = entries += 1
    })
    body
    runtime.traceTo(null)
    entries
  }

  it("enters the runtime once per root Var.set, Var.update, Var.set batch and EventBus.emit, whatever the graph below") {
    val runtime = Runtime.confined()
    val owner = runtime.run(new ManualOwner)
    val seen = mutable.Buffer[Int]()
    val source = runtime.run(Var(0))
    val other = runtime.run(Var(0))
    val bus = runtime.run(new EventBus[Int])
    runtime.run {
      (1 to 5).foldLeft(source.signal)((signal, _) => signal.map(_ + 1)).foreach(seen += _)(owner)
      source.signal.combineWith(other.signal).foreach { case (a, b) => seen += a + b }(owner)
      bus.events.map(_ * 2).foreach(seen += _)(owner)
    }
    entriesOf(runtime)(source.set(1)) shouldBe 1
    entriesOf(runtime)(source.update(_ + 1)) shouldBe 1
    entriesOf(runtime)(Var.set(source -> 3, other -> 4)) shouldBe 1
    entriesOf(runtime)(bus.emit(5)) shouldBe 1
    seen.nonEmpty shouldBe true
    runtime.run(owner.killSubscriptions())
  }
}
