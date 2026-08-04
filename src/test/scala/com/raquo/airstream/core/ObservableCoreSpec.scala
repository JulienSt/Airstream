package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.fixtures.TestableOwner

import scala.collection.mutable

/** Pins down the mutable observer-list behavior shared by every WritableObservable. */
class ObservableCoreSpec extends UnitSpec {

  it("notifies external observers in subscription order") {
    val owner = new TestableOwner
    val bus = new EventBus[Int]
    val seen = mutable.Buffer[String]()

    bus.events.foreach(value => seen += s"first:$value")(owner)
    bus.events.foreach(value => seen += s"second:$value")(owner)
    bus.events.foreach(value => seen += s"third:$value")(owner)

    bus.emit(1)
    bus.emit(2)

    seen shouldBe mutable.Buffer("first:1", "second:1", "third:1", "first:2", "second:2", "third:2")
    owner.killSubscriptions()
  }

  it("keeps duplicate subscriptions independent and ordered") {
    val owner = new TestableOwner
    val bus = new EventBus[Int]
    val seen = mutable.Buffer[Int]()
    val observer = Observer[Int](seen += _)

    val first = bus.events.addObserver(observer)(owner)
    bus.events.addObserver(observer)(owner)
    bus.emit(1)
    first.kill()
    bus.emit(2)

    seen shouldBe mutable.Buffer(1, 1, 2)
    owner.killSubscriptions()
  }

  it("lets the remaining observers receive the current event when an observer removes itself") {
    val owner = new TestableOwner
    val bus = new EventBus[Int]
    val seen = mutable.Buffer[String]()
    var killFirst = () => ()

    val first = bus.events.foreach { value =>
      seen += s"first:$value"
      killFirst()
    }(owner)
    killFirst = () => first.kill()
    bus.events.foreach(value => seen += s"second:$value")(owner)

    bus.emit(1)
    bus.emit(2)

    seen shouldBe mutable.Buffer("first:1", "second:1", "second:2")
    owner.killSubscriptions()
  }

  it("delays removing a later observer until the current firing has finished") {
    val owner = new TestableOwner
    val bus = new EventBus[Int]
    val seen = mutable.Buffer[String]()
    var killSecond = () => ()

    bus.events.foreach { value =>
      seen += s"first:$value"
      if (value == 1) killSecond()
    }(owner)
    val second = bus.events.foreach(value => seen += s"second:$value")(owner)
    killSecond = () => second.kill()

    bus.emit(1)
    bus.emit(2)

    seen shouldBe mutable.Buffer("first:1", "second:1", "first:2")
    owner.killSubscriptions()
  }

  it("applies a removal before a recursively requested next transaction fires") {
    val owner = new TestableOwner
    val bus = new EventBus[Int]
    val seen = mutable.Buffer[String]()
    var killSecond = () => ()

    bus.events.foreach { value =>
      seen += s"first:$value"
      if (value == 1) {
        killSecond()
        bus.emit(2)
      }
    }(owner)
    val second = bus.events.foreach(value => seen += s"second:$value")(owner)
    killSecond = () => second.kill()

    bus.emit(1)

    seen shouldBe mutable.Buffer("first:1", "second:1", "first:2")
    owner.killSubscriptions()
  }

  it("preserves the relative order around an observer removed between firings") {
    val owner = new TestableOwner
    val bus = new EventBus[Int]
    val seen = mutable.Buffer[String]()

    bus.events.foreach(_ => seen += "first")(owner)
    val middle = bus.events.foreach(_ => seen += "middle")(owner)
    bus.events.foreach(_ => seen += "last")(owner)

    bus.emit(1)
    middle.kill()
    bus.emit(2)

    seen shouldBe mutable.Buffer("first", "middle", "last", "first", "last")
    owner.killSubscriptions()
  }
}
