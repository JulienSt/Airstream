package com.raquo.airstream.timing

import com.raquo.airstream.AsyncUnitSpec
import com.raquo.airstream.core.{EventStream, Runtime, Signal}
import com.raquo.airstream.ownership.ManualOwner

import scala.collection.mutable
import scala.scalajs.js

/** Asynchronous ingress of the JavaScript promise adapters: a promise callback runs from the event loop's
  * microtask queue, outside every turn, so it hands the settlement to the owning runtime, which applies it in a turn of its
  * own. On a scheduled runtime the event loop may not enter the graph directly at all.
  */
class JsPromiseIngressSpec extends AsyncUnitSpec {

  it("delivers a promise stream's value into a scheduled runtime in a turn of that runtime") {
    val runtime = Runtime.scheduled(executionContext)
    val seen = mutable.Buffer[Int]()
    runtime.submit {
      EventStream.fromJsPromise(js.Promise.resolve[Int](3)).foreach(seen += _)(new ManualOwner)
    }
    delay(10) {
      seen.toList shouldBe List(3)
    }
  }

  it("updates a promise signal in a scheduled runtime in a turn of that runtime") {
    val runtime = Runtime.scheduled(executionContext)
    val seen = mutable.Buffer[Option[Int]]()
    runtime.submit {
      Signal.fromJsPromise(js.Promise.resolve[Int](4)).foreach(seen += _)(new ManualOwner)
    }
    delay(10) {
      seen.toList shouldBe List(None, Some(4))
    }
  }
}
