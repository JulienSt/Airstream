package com.raquo.airstream.web

import com.raquo.airstream.AsyncUnitSpec
import com.raquo.airstream.core.{EventStream, Runtime}
import com.raquo.airstream.ownership.ManualOwner
import org.scalajs.dom

import scala.collection.mutable
import scala.scalajs.js

/** Asynchronous ingress of the browser-host adapters: a timer, animation-frame or XMLHttpRequest callback runs from
  * the event loop, outside every turn, so it hands the occurrence to the owning runtime, which applies it, pending state
  * included, in a turn of its own. The animation frame is polyfilled with a zero timeout: jsdom offers none by default.
  */
class JsHostIngressSpec extends AsyncUnitSpec {

  private def installFramePolyfill(): Unit = {
    val window = dom.window.asInstanceOf[js.Dynamic]
    val request: js.Function1[js.Function1[Double, Any], Int] = { callback =>
      js.timers.setTimeout(0)(callback(js.Date.now()))
      1
    }
    val cancel: js.Function1[Int, Unit] = _ => ()
    window.requestAnimationFrame = request
    window.cancelAnimationFrame = cancel
  }

  it("delivers an animation frame into a scheduled runtime in a turn of that runtime") {
    installFramePolyfill()
    val runtime = Runtime.scheduled(executionContext)
    val seen = mutable.Buffer[Int]()
    runtime.submit {
      EventStream.fromValue(7).throttleWithAnimationFrame.foreach(seen += _)(new ManualOwner)
    }
    delay(30) {
      seen.toList shouldBe List(7)
    }
  }

  it("delivers an XMLHttpRequest failure into a scheduled runtime in a turn of that runtime") {
    val runtime = Runtime.scheduled(executionContext)
    val seen = mutable.Buffer[String]()
    runtime.submit {
      AjaxStream
        .get("http://127.0.0.1:9/unreachable")
        .recoverToTry
        .foreach(result => seen += result.fold(_.getClass.getSimpleName, _ => "loaded"))(new ManualOwner)
    }
    delay(500) {
      seen.toList shouldBe List("AjaxNetworkError")
    }
  }

  it("delivers a timer callback into a scheduled runtime in a turn of that runtime") {
    val runtime = Runtime.scheduled(executionContext)
    val seen = mutable.Buffer[Int]()
    runtime.submit {
      EventStream.fromValue(8).delay(0).foreach(seen += _)(new ManualOwner)
    }
    delay(30) {
      seen.toList shouldBe List(8)
    }
  }
}
