package com.raquo.airstream.combine

import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.fixtures.TestableOwner
import com.raquo.airstream.state.Var
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Proves that generated instance combinators, not only their implementation classes, are available on Native. */
class NativeCombineApiSpec extends AnyFunSpec with Matchers {

  it("combines two signal instances with an explicit function") {
    val owner = new TestableOwner
    val left = Var(2)
    val right = Var(3)
    val seen = mutable.Buffer[Int]()

    left.signal.combineWithFn(right.signal)(_ + _).foreach(seen += _)(owner)
    right.set(5)

    seen shouldBe mutable.Buffer(5, 7)
    owner.killSubscriptions()
  }

  it("uses tuplez composition for the generated signal combineWith API") {
    val owner = new TestableOwner
    val left = Var(2)
    val right = Var("three")
    val seen = mutable.Buffer[(Int, String)]()

    left.signal.combineWith(right.signal).foreach(seen += _)(owner)

    seen shouldBe mutable.Buffer((2, "three"))
    owner.killSubscriptions()
  }

  it("combines two event-stream instances with an explicit function") {
    val owner = new TestableOwner
    val left = new EventBus[Int]
    val right = new EventBus[Int]
    val seen = mutable.Buffer[Int]()

    left.events.combineWithFn(right.events)(_ + _).foreach(seen += _)(owner)
    left.emit(1)
    right.emit(10)
    left.emit(2)

    seen shouldBe mutable.Buffer(11, 12)
    owner.killSubscriptions()
  }

  it("uses tuplez composition when sampling a signal from an event stream") {
    val owner = new TestableOwner
    val events = new EventBus[Int]
    val current = Var("value")
    val seen = mutable.Buffer[(Int, String)]()

    events.events.withCurrentValueOf(current.signal).foreach(seen += _)(owner)
    events.emit(4)

    seen shouldBe mutable.Buffer((4, "value"))
    owner.killSubscriptions()
  }
}
