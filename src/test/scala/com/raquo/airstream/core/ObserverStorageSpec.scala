package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.platform.JsArray

import scala.collection.mutable

/** Lazy default storage and explicit custom-source storage follow the same delivery and ownership rules. */
class ObserverStorageSpec extends UnitSpec {

  private class Source extends WritableStream[Int] {
    override protected val topoRank: Int = 1
    override protected def onWillStart(): Unit = ()
    def emit(value: Int): Unit = Transaction(transaction => fireValue(value, transaction))
    def count: Int = numAllObservers
    def externalHandle: ObserverList[Observer[Int]] = externalObservers
    def internalHandle: ObserverList[InternalObserver[Int]] = internalObservers
  }

  final private class PreallocatedSource(
    external: ObserverList[Observer[Int]],
    internal: ObserverList[InternalObserver[Int]]
  ) extends Source {
    externalObservers = external
    internalObservers = internal
  }

  private def checkLifecycle(source: Source): Unit = {
    val owner = new ManualOwner
    val seen = mutable.Buffer.empty[String]
    source.count shouldBe 0
    source.emit(0)
    source.foreach(value => seen += s"direct:$value")(owner)
    source.map(_ * 10).foreach(value => seen += s"mapped:$value")(owner)
    source.count shouldBe 2
    source.emit(1)
    seen.toList shouldBe List("direct:1", "mapped:10")
    owner.killSubscriptions()
    source.count shouldBe 0
    source.emit(2)
    seen.toList shouldBe List("direct:1", "mapped:10")
    source.map(_ * 10).foreach(value => seen += s"restarted:$value")(owner)
    source.emit(3)
    seen.toList shouldBe List("direct:1", "mapped:10", "restarted:30")
    owner.killSubscriptions()
    source.count shouldBe 0
  }

  it("starts, stops and restarts both observer kinds from initially unused storage") {
    checkLifecycle(new Source)
  }

  it("honors explicitly installed storage and retains its aliases through teardown and restart") {
    val external = new ObserverList(JsArray[Observer[Int]]())
    val internal = new ObserverList(JsArray[InternalObserver[Int]]())
    val source = new PreallocatedSource(external, internal)
    checkLifecycle(source)
    val owner = new ManualOwner
    source.foreach(_ => ())(owner)
    source.map(identity).foreach(_ => ())(owner)
    external.length shouldBe 1
    internal.length shouldBe 1
    owner.killSubscriptions()
    external.length shouldBe 0
    internal.length shouldBe 0
  }

  it("returns usable live handles even when a custom source accesses storage before its first subscription") {
    val source = new Source
    val external = source.externalHandle
    val internal = source.internalHandle
    val owner = new ManualOwner
    source.foreach(_ => ())(owner)
    source.map(identity).foreach(_ => ())(owner)
    external.length shouldBe 1
    internal.length shouldBe 1
    owner.killSubscriptions()
    external.length shouldBe 0
    internal.length shouldBe 0
  }

  it("delivers to external and internal observers registered during the current delivery") {
    val source = new Source
    val owner = new ManualOwner
    val seen = mutable.Buffer.empty[String]
    source.foreach { value =>
      seen += s"first:$value"
      if (value == 1) {
        source.foreach(next => seen += s"late:$next")(owner)
        source.map(_ * 10).foreach(next => seen += s"mapped:$next")(owner)
      }
    }(owner)
    source.emit(1)
    seen.toList shouldBe List("first:1", "late:1", "mapped:10")
    source.emit(2)
    seen.toList shouldBe List("first:1", "late:1", "mapped:10", "first:2", "late:2", "mapped:20")
    owner.killSubscriptions()
    source.count shouldBe 0
  }
}
