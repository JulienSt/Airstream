package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.fixtures.TestableOwner

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/** Subscription ownership is tied to the observer instance, even when two observers define the same value equality. */
class ObserverIdentitySpec extends UnitSpec {

  final private class EqualObserver(label: String, received: mutable.Buffer[String]) extends Observer[Int] {

    override def equals(other: Any): Boolean = other.isInstanceOf[EqualObserver]

    override def hashCode(): Int = 1

    override def onNext(value: Int): Unit = {
      received += s"$label:$value"
    }

    override def onError(error: Throwable): Unit = throw error

    override def onTry(value: Try[Int]): Unit = value match {
      case Success(next) => onNext(next)
      case Failure(error) => onError(error)
    }
  }

  it("kills the requested observer instance without removing an equal but distinct subscriber") {
    val owner = new TestableOwner
    val bus = new EventBus[Int]
    val received = mutable.Buffer[String]()
    val first = new EqualObserver("first", received)
    val second = new EqualObserver("second", received)
    (first eq second) shouldBe false
    first shouldBe second
    bus.events.addObserver(first)(owner)
    val secondSubscription = bus.events.addObserver(second)(owner)

    try {
      bus.emit(1)
      received.toList shouldBe List("first:1", "second:1")
      received.clear()
      secondSubscription.kill()
      bus.emit(2)
      received.toList shouldBe List("first:2")
      BaseObservable.numAllObservers(bus.events) shouldBe 1
    } finally owner.killSubscriptions()

    received.clear()
    bus.emit(3)
    received.toList shouldBe Nil
    BaseObservable.numAllObservers(bus.events) shouldBe 0
  }
}
