package com.raquo.airstream.status

import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.fixtures.TestableOwner
import com.raquo.airstream.platform.Timers
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Status tracking contracts driven by the same controllable clock as Native timing operators. */
class NativeStatusSpec extends AnyFunSpec with Matchers {

  it("reports Pending immediately and the mapped delayed output at its deadline") {
    Timers.clearAll()
    val owner = new TestableOwner
    val bus = new EventBus[Int]
    val seen = mutable.Buffer.empty[Status[Int, String]]
    bus.events.delayWithStatus(30).mapOutput(_.toString).foreach(seen += _)(owner)

    bus.emit(1)
    seen shouldBe mutable.Buffer(Pending(1))
    Timers.runDue(29)
    seen shouldBe mutable.Buffer(Pending(1))
    Timers.runDue(30)
    seen shouldBe mutable.Buffer(Pending(1), Resolved(1, "1", 1))
    owner.killSubscriptions()
  }

  it("keeps one debounced output stream and resolves against the latest pending input") {
    Timers.clearAll()
    val owner = new TestableOwner
    val bus = new EventBus[Int]
    val seen = mutable.Buffer.empty[Status[Int, Int]]
    bus.events.debounceWithStatus(100).foreach(seen += _)(owner)

    bus.emit(1)
    Timers.runDue(20)
    bus.emit(2)
    seen shouldBe mutable.Buffer(Pending(1), Pending(2))
    Timers.runDue(119)
    seen shouldBe mutable.Buffer(Pending(1), Pending(2))
    Timers.runDue(120)
    seen shouldBe mutable.Buffer(Pending(1), Pending(2), Resolved(2, 2, 1))
    owner.killSubscriptions()
  }
}
