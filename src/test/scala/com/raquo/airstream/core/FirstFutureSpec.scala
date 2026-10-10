package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.{DynamicOwner, ManualOwner}

import java.util.concurrent.CancellationException
import scala.util.{Failure, Success}

class FirstFutureSpec extends UnitSpec {
  it("retains the first event after removing its observation") {
    val owner = new ManualOwner
    val bus = new EventBus[Int]
    val result = bus.events.firstFuture()(using owner)
    result.value shouldBe None
    bus.writer.onNext(42)
    bus.writer.onNext(43)
    result.value shouldBe Some(Success(42))
    owner.killSubscriptions()
    result.value shouldBe Some(Success(42))
  }

  it("retains the first stream failure") {
    val owner = new ManualOwner
    val bus = new EventBus[Int]
    val problem = new IllegalArgumentException("source failed")
    val result = bus.events.firstFuture()(using owner)
    bus.writer.onError(problem)
    bus.writer.onNext(42)
    result.value shouldBe Some(Failure(problem))
    owner.killSubscriptions()
  }

  it("handles a source that emits while its observation starts") {
    val owner = new ManualOwner
    val result = EventStream.fromSeq(Vector(42, 43)).firstFuture()(using owner)
    result.value shouldBe Some(Success(42))
    owner.killSubscriptions()
    result.value shouldBe Some(Success(42))
  }

  it("settles cancellation and detaches when its owner ends before an event") {
    val owner = new ManualOwner
    val bus = new EventBus[Int]
    val result = bus.events.firstFuture()(using owner)
    owner.killSubscriptions()
    result.value.get.failed.get.isInstanceOf[CancellationException] shouldBe true
    bus.writer.onNext(42)
    result.value.get.failed.get.isInstanceOf[CancellationException] shouldBe true
  }

  it("starts with its dynamic owner's lifetime and retains the result across remount") {
    val owner = new DynamicOwner(() => fail("ended owner used"))
    val bus = new EventBus[Int]
    val result = bus.events.firstFuture(owner)
    bus.writer.onNext(41)
    result.value shouldBe None
    owner.activate()
    bus.writer.onNext(42)
    owner.deactivate()
    owner.activate()
    bus.writer.onNext(43)
    result.value shouldBe Some(Success(42))
    owner.deactivate()
  }

  it("retains cancellation when its dynamic owner remounts after ending the first observation") {
    val owner = new DynamicOwner(() => fail("ended owner used"))
    val bus = new EventBus[Int]
    val result = bus.events.firstFuture(owner)
    owner.activate()
    owner.deactivate()
    result.value.get.failed.get.isInstanceOf[CancellationException] shouldBe true
    owner.activate()
    bus.writer.onNext(42)
    result.value.get.failed.get.isInstanceOf[CancellationException] shouldBe true
    owner.deactivate()
  }
}
