package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.fixtures.TestableOwner
import com.raquo.airstream.state.Var

import scala.collection.mutable

/** Regression: a kill issued during a new observer's subscribe-time delivery. When the new observer's first delivery
  * kills another observer of the same signal, the observer count returns to one. `maybeStart` used to read that count
  * as "first observer just arrived" and ran `onStart` a second time, so a derived signal registered with its parent
  * twice and delivered every later update twice. Starting is decided by whether the observable was started before the
  * observer was added, not by the count afterwards, so a kill during the delivery cannot restart it.
  */
class SubscribeTimeKillSpec extends UnitSpec {

  it("does not start a derived signal a second time when its new observer kills another observer during the first delivery") {
    val owner = new TestableOwner
    val source = Var(3)
    val derived = source.signal.map(_ + 1)
    val received = mutable.Buffer[Int]()
    val first = derived.addObserver(Observer[Int](_ => ()))(owner)
    derived.addObserver(Observer[Int] { value =>
      received += value
      if (!first.isKilled) first.kill()
    })(owner)

    source.update(_ + 1)

    received.toList shouldBe List(4, 5)
    BaseObservable.numAllObservers(source.signal) shouldBe 1
    owner.killSubscriptions()
    BaseObservable.numAllObservers(source.signal) shouldBe 0
  }

  it("does not start a stream a second time when its new observer kills another observer during a shared start") {
    val owner = new TestableOwner
    val bus = new com.raquo.airstream.eventbus.EventBus[Int]
    val mapped = bus.events.map(_ * 10)
    val received = mutable.Buffer[Int]()
    val first = mapped.addObserver(Observer[Int](_ => ()))(owner)
    mapped.addObserver(Observer[Int] { value =>
      received += value
      if (!first.isKilled) first.kill()
    })(owner)

    bus.emit(1)
    bus.emit(2)

    received.toList shouldBe List(10, 20)
    BaseObservable.numAllObservers(bus.events) shouldBe 1
    owner.killSubscriptions()
    BaseObservable.numAllObservers(bus.events) shouldBe 0
  }
}
