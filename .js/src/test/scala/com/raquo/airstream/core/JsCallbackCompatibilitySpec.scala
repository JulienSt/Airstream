package com.raquo.airstream.core

import com.raquo.airstream.fixtures.TestableOwner
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.scalajs.js

class JsCallbackCompatibilitySpec extends AnyFunSpec with Matchers {

  it("keeps EventStream.withJsCallback directly JavaScript-callable") {
    val owner = new TestableOwner
    val (stream, callback): (EventStream[Int], js.Function1[Int, Unit]) = EventStream.withJsCallback[Int]
    var seen = List.empty[Int]
    stream.foreach(value => seen = seen :+ value)(owner)

    callback(2)
    callback(3)

    seen shouldBe List(2, 3)
    owner.killSubscriptions()
  }

  it("keeps Observer callbacks and Sink conversion on the public JavaScript types") {
    var seen = List.empty[Int]
    val observer = Observer[Int](value => seen = seen :+ value)
    val callback: js.Function1[Int, Unit] = observer.toJsFn1
    val sink: Sink[Int] = callback

    sink.toObserver.onNext(4)
    callback(5)

    seen shouldBe List(4, 5)
  }
}
