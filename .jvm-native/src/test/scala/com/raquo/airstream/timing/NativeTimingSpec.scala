package com.raquo.airstream.timing

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.fixtures.TestableOwner
import com.raquo.airstream.platform.Timers
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Timing operator contracts driven entirely by the Native event-loop clock. */
class NativeTimingSpec extends AnyFunSpec with Matchers {

  private def freshClock(): Unit = Timers.clearAll()

  describe("delay") {

    it("emits every value at its exact deadline and preserves source order") {
      freshClock()
      val owner = new TestableOwner
      val bus = new EventBus[Int]
      val seen = mutable.Buffer.empty[Int]
      bus.events.delay(30).foreach(seen += _)(owner)

      bus.emit(1)
      bus.emit(2)
      Timers.runDue(29)
      seen shouldBe empty
      Timers.runDue(30)
      seen shouldBe mutable.Buffer(1, 2)
      owner.killSubscriptions()
    }

    it("cancels pending values when its last subscription stops") {
      freshClock()
      val owner = new TestableOwner
      val bus = new EventBus[Int]
      val seen = mutable.Buffer.empty[Int]
      bus.events.delay(10).foreach(seen += _)(owner)

      bus.emit(1)
      owner.killSubscriptions()
      Timers.pendingCount shouldBe 0
      Timers.runDue(10)
      seen shouldBe empty
    }
  }

  describe("debounce") {

    it("emits only the latest value after a full quiet interval") {
      freshClock()
      val owner = new TestableOwner
      val bus = new EventBus[Int]
      val seen = mutable.Buffer.empty[Int]
      bus.events.debounce(100).foreach(seen += _)(owner)

      bus.emit(1)
      Timers.runDue(40)
      bus.emit(2)
      Timers.runDue(139)
      seen shouldBe empty
      Timers.runDue(140)
      seen shouldBe mutable.Buffer(2)
      owner.killSubscriptions()
    }

    it("forgets the pending value when stopped and starts a fresh interval after restart") {
      freshClock()
      val firstOwner = new TestableOwner
      val secondOwner = new TestableOwner
      val bus = new EventBus[Int]
      val seen = mutable.Buffer.empty[Int]
      val debounced = bus.events.debounce(50)
      debounced.foreach(seen += _)(firstOwner)

      bus.emit(1)
      firstOwner.killSubscriptions()
      Timers.runDue(50)
      seen shouldBe empty

      debounced.foreach(seen += _)(secondOwner)
      bus.emit(2)
      Timers.runDue(99)
      seen shouldBe empty
      Timers.runDue(100)
      seen shouldBe mutable.Buffer(2)
      secondOwner.killSubscriptions()
    }
  }

  describe("throttle") {

    it("emits the leading value asynchronously and then the latest value at the interval boundary") {
      freshClock()
      val owner = new TestableOwner
      val bus = new EventBus[Int]
      val seen = mutable.Buffer.empty[Int]
      bus.events.throttle(100, leading = true).foreach(seen += _)(owner)

      bus.emit(1)
      seen shouldBe empty
      Timers.runDue(0)
      seen shouldBe mutable.Buffer(1)
      Timers.runDue(20)
      bus.emit(2)
      bus.emit(3)
      Timers.runDue(99)
      seen shouldBe mutable.Buffer(1)
      Timers.runDue(100)
      seen shouldBe mutable.Buffer(1, 3)
      owner.killSubscriptions()
    }

    it("withholds the first value when leading is false and keeps the latest scheduled value") {
      freshClock()
      val owner = new TestableOwner
      val bus = new EventBus[Int]
      val seen = mutable.Buffer.empty[Int]
      bus.events.throttle(100, leading = false).foreach(seen += _)(owner)

      bus.emit(1)
      Timers.runDue(20)
      bus.emit(2)
      bus.emit(3)
      Timers.runDue(119)
      seen shouldBe empty
      Timers.runDue(120)
      seen shouldBe mutable.Buffer(3)
      owner.killSubscriptions()
    }
  }

  describe("periodic streams") {

    it("ticks at driven deadlines and resets to its initial value after a stop") {
      freshClock()
      val firstOwner = new TestableOwner
      val secondOwner = new TestableOwner
      val seen = mutable.Buffer.empty[Int]
      val periodic = EventStream.periodic(intervalMs = 25, resetOnStop = true)
      periodic.foreach(seen += _)(firstOwner)

      seen shouldBe mutable.Buffer(0)
      Timers.runDue(24)
      seen shouldBe mutable.Buffer(0)
      Timers.runDue(25)
      seen shouldBe mutable.Buffer(0, 1)
      firstOwner.killSubscriptions()
      Timers.runDue(100)
      seen shouldBe mutable.Buffer(0, 1)

      periodic.foreach(seen += _)(secondOwner)
      seen shouldBe mutable.Buffer(0, 1, 0)
      secondOwner.killSubscriptions()
    }

    it("retains state across stops and honours changing intervals") {
      freshClock()
      val firstOwner = new TestableOwner
      val secondOwner = new TestableOwner
      val seen = mutable.Buffer.empty[Int]
      val periodic = new PeriodicStream[Int](
        initial = 0,
        next = value => Some((value + 1, if (value == 0) 10 else 20)),
        resetOnStop = false
      )
      periodic.foreach(seen += _)(firstOwner)

      Timers.runDue(10)
      seen shouldBe mutable.Buffer(0, 1)
      Timers.runDue(29)
      seen shouldBe mutable.Buffer(0, 1)
      Timers.runDue(30)
      seen shouldBe mutable.Buffer(0, 1, 2)
      firstOwner.killSubscriptions()

      periodic.foreach(seen += _)(secondOwner)
      seen shouldBe mutable.Buffer(0, 1, 2, 3)
      secondOwner.killSubscriptions()
    }
  }
}
