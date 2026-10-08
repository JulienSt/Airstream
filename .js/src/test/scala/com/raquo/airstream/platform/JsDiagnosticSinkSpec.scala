package com.raquo.airstream.platform

import com.raquo.airstream.UnitSpec

import scala.collection.mutable
import scala.scalajs.js

/** Unit diagnostics omit the console's second argument; null and ordinary payloads remain actual arguments. */
class JsDiagnosticSinkSpec extends UnitSpec {

  it("preserves console methods, argument counts and payload identity after resetting a custom sink") {
    val failure = new IllegalStateException("formatting failed")
    val calls = JsDiagnosticSinkSpec.capture {
      DevTools.setSink((_, _, _) => fail("reset must restore the console"))
      DevTools.resetSink()
      DevTools.log("value", 42)
      DevTools.log("null", null)
      DevTools.log("unit", ())
      DevTools.warn("warning")
      DevTools.error("error")
      DevTools.consoleErrorCallbackFailed(failure)
    }
    calls.map(_._1) shouldBe List("log", "log", "log", "warn", "error", "error")
    calls.map(_._2.length) shouldBe List(2, 2, 1, 1, 1, 2)
    calls.map(_._2.head) shouldBe List("value", "null", "unit", "warning", "error", "Error in AirstreamError.consoleErrorCallback:")
    calls(0)._2(1) shouldBe 42
    calls(1)._2(1) shouldBe null
    calls(5)._2(1) shouldBe failure
  }
}

private object JsDiagnosticSinkSpec {

  private val calls = mutable.Buffer[(String, List[js.Any])]()

  private class RecordingConsole extends js.Object {
    def log(values: js.Any*): Unit = JsDiagnosticSinkSpec.calls += (("log", values.toList))
    def warn(values: js.Any*): Unit = JsDiagnosticSinkSpec.calls += (("warn", values.toList))
    def error(values: js.Any*): Unit = JsDiagnosticSinkSpec.calls += (("error", values.toList))
  }

  def capture(body: => Unit): List[(String, List[js.Any])] = {
    val console = js.Dynamic.global.console
    val previousLog = console.log
    val previousWarn = console.warn
    val previousError = console.error
    val recorder = (new RecordingConsole).asInstanceOf[js.Dynamic]
    calls.clear()
    console.log = recorder.log
    console.warn = recorder.warn
    console.error = recorder.error
    try {
      body
      calls.toList
    } finally {
      console.log = previousLog
      console.warn = previousWarn
      console.error = previousError
      DevTools.resetSink()
    }
  }
}
