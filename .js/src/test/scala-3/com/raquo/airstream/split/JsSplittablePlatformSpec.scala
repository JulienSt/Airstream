package com.raquo.airstream.split

import scala.scalajs.js

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.fixtures.TestableOwner
import com.raquo.ew.JsVector

/** Keeps the JavaScript-only collection instances covered after the shared split specs became platform-neutral. */
class JsSplittablePlatformSpec extends AnyFunSpec with Matchers {

  private final case class Row(id: String, value: Int)

  it("splits a raw JavaScript array while retaining its collection shape") {
    val owner = new TestableOwner
    val bus = new EventBus[js.Array[Row]]
    var latest = js.Array[String]()

    bus.events.splitSeq(_.id)(child => s"${child.key}:${child.now().value}").foreach(latest = _)(owner)
    bus.emit(js.Array(Row("a", 1), Row("b", 2)))

    latest.length shouldBe 2
    latest(0) shouldBe "a:1"
    latest(1) shouldBe "b:2"
    owner.killSubscriptions()
  }

  it("creates and maps the JavaScript vector instance in source order") {
    val instance = implicitly[Splittable[JsVector]]
    val vector = instance.create(Seq(1, 2, 3))
    val mapped = instance.map(vector, (value: Int) => value * 2)

    mapped.length shouldBe 3
    mapped(0) shouldBe 2
    mapped(1) shouldBe 4
    mapped(2) shouldBe 6
  }
}
