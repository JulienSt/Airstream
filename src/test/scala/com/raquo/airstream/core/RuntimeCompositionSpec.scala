package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.CrossRuntimeEdge
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import scala.collection.mutable

/** A synchronous graph edge connects observables of one runtime: combining, merging, sampling, adding a
  * source, owning a subscription and writing a batch across runtimes are rejected at validation, before any subscription
  * or value changes. The rejection is an exception at the call site, so the caller learns about it
  * where the mixed-runtime edge was requested.
  */
class RuntimeCompositionSpec extends UnitSpec {

  private def inOtherRuntime[A](body: => A): A = Runtime.confined().run(body)

  it("rejects combining signals of two runtimes before any subscription changes") {
    val owner = new ManualOwner
    val a = Var(1)
    val b = inOtherRuntime(Var(2))
    val rejection = intercept[CrossRuntimeEdge] {
      a.signal.combineWith(b.signal)
    }
    rejection.runtimes.toSet shouldBe Set(a.runtime, b.runtime)
    BaseObservable.numAllObservers(a.signal) shouldBe 0
    BaseObservable.numAllObservers(b.signal) shouldBe 0
    owner.killSubscriptions()
  }

  it("rejects merging streams of two runtimes") {
    val a = new EventBus[Int]
    val b = inOtherRuntime(new EventBus[Int])
    intercept[CrossRuntimeEdge] {
      EventStream.merge(a.events, b.events)
    }
    BaseObservable.numAllObservers(a.events) shouldBe 0
    BaseObservable.numAllObservers(b.events) shouldBe 0
  }

  it("rejects sampling a signal of another runtime") {
    val a = new EventBus[Int]
    val b = inOtherRuntime(Var(2))
    intercept[CrossRuntimeEdge] {
      a.events.withCurrentValueOf(b.signal)
    }
    BaseObservable.numAllObservers(b.signal) shouldBe 0
  }

  it("rejects adding a source of another runtime to an event bus, leaving the bus without the source") {
    val owner = new ManualOwner
    val bus = new EventBus[Int]
    val source = inOtherRuntime(new EventBus[Int])
    val seen = mutable.Buffer[Int]()
    bus.events.foreach(seen += _)(owner)
    intercept[CrossRuntimeEdge] {
      bus.writer.addSource(source.events)(owner)
    }
    BaseObservable.numAllObservers(source.events) shouldBe 0
    owner.killSubscriptions()
  }

  it("rejects an owner of another runtime before the observer is added") {
    val foreignOwner = inOtherRuntime(new ManualOwner)
    val bus = new EventBus[Int]
    intercept[CrossRuntimeEdge] {
      bus.events.foreach(_ => ())(foreignOwner)
    }
    BaseObservable.numAllObservers(bus.events) shouldBe 0
  }

  it("rejects a mixed-runtime Var batch as a whole, before any write") {
    val a = Var(1)
    val b = inOtherRuntime(Var(2))
    intercept[CrossRuntimeEdge] {
      Var.set(a -> 10, b -> 20)
    }
    a.now() shouldBe 1
    b.now() shouldBe 2
    intercept[CrossRuntimeEdge] {
      Var.update(a -> ((_: Int) + 1), b -> ((_: Int) + 1))
    }
    a.now() shouldBe 1
    b.now() shouldBe 2
  }

  it("rejects a mixed-runtime EventBus batch before any emission") {
    val owner = new ManualOwner
    val a = new EventBus[Int]
    val b = inOtherRuntime(new EventBus[Int])
    val seen = mutable.Buffer[Int]()
    a.events.foreach(seen += _)(owner)
    intercept[CrossRuntimeEdge] {
      EventBus.emit(a -> 1, b -> 2)
    }
    seen.toList shouldBe Nil
    owner.killSubscriptions()
  }

  it("keeps a derived Var in its underlying Var's runtime") {
    val runtime = Runtime.confined()
    val v = runtime.run(Var(1))
    val zoomed = v.zoom(_ + 1)((_, next) => next - 1)
    zoomed.runtime shouldBe runtime
    zoomed.set(5)
    v.now() shouldBe 4
  }

  it("rejects a dynamic inner stream of another runtime with an unhandled error, without subscribing to it") {
    val owner = new ManualOwner
    val errors = mutable.Buffer[Throwable]()
    val errorCallback: Throwable => Unit = errors += _
    AirstreamError.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
    AirstreamError.registerUnhandledErrorCallback(errorCallback)
    try {
      val outer = new EventBus[Int]
      val foreign = inOtherRuntime(new EventBus[Int])
      val own = new EventBus[Int]
      val switched = outer.events.flatMapSwitch(n => if (n == 1) own.events else foreign.events)
      switched.foreach(_ => ())(owner)
      outer.emit(1)
      BaseObservable.numAllObservers(own.events) shouldBe 1
      outer.emit(2)
      errors.collect { case rejection: CrossRuntimeEdge => rejection }.size shouldBe 1
      BaseObservable.numAllObservers(foreign.events) shouldBe 0
    } finally {
      AirstreamError.unregisterUnhandledErrorCallback(errorCallback)
      AirstreamError.registerUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
      owner.killSubscriptions()
    }
  }
}
