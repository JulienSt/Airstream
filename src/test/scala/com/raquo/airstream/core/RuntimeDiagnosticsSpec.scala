package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.platform.{DevTools, DiagnosticLevel, DiagnosticSinks}

import scala.collection.mutable

/** The diagnostics sink is a setting of a runtime, like the feature flags: `DevTools.setSink` configures the default
  * runtime, a confined runtime takes the sink in force when it is created, and a diagnostic goes to the sink of the
  * runtime whose turn reports it. A terminal application that owns several runtimes can therefore route each one's
  * output separately.
  */
class RuntimeDiagnosticsSpec extends UnitSpec {

  it("sends a diagnostic to the sink of the runtime it is reported in, snapshotting the default's sink at creation") {
    val first = mutable.Buffer[String]()
    val second = mutable.Buffer[String]()
    DevTools.setSink((_, prefix, _) => first += prefix)
    try {
      val runtime = Runtime.confined()
      DevTools.setSink((_, prefix, _) => second += prefix)
      runtime.run(DevTools.log("inside", ()))
      DevTools.log("outside", ())
      first.toList shouldBe List("inside")
      second.toList shouldBe List("outside")
    } finally {
      DevTools.resetSink()
    }
  }

  it("preserves the configured function identity and every diagnostic level and payload") {
    val seen = mutable.Buffer[(DiagnosticLevel, String, Any)]()
    val destination: (DiagnosticLevel, String, Any) => Unit = (level, prefix, value) => seen += ((level, prefix, value))
    val failure = new IllegalStateException("formatting failed")
    DevTools.setSink(destination)
    try {
      val runtime = Runtime.confined()
      (Runtime.default.diagnostics eq destination) shouldBe true
      (runtime.diagnostics eq destination) shouldBe true
      runtime.run {
        DevTools.log("value", 42)
        DevTools.log("null", null)
        DevTools.log("unit", ())
        DevTools.warn("warning")
        DevTools.error("error")
        DevTools.consoleErrorCallbackFailed(failure)
      }
      seen.toList shouldBe List(
        (DiagnosticLevel.Log, "value", 42), (DiagnosticLevel.Log, "null", null), (DiagnosticLevel.Log, "unit", ()), (DiagnosticLevel.Warn, "warning", ()), (DiagnosticLevel.Error, "error", ()), (DiagnosticLevel.Error, "Error in AirstreamError.consoleErrorCallback:", failure)
      )
      DevTools.resetSink()
      (Runtime.default.diagnostics eq DiagnosticSinks.default) shouldBe true
      (runtime.diagnostics eq destination) shouldBe true
      runtime.run(DevTools.warn("retained"))
      seen.last shouldBe ((DiagnosticLevel.Warn, "retained", ()))
    } finally {
      DevTools.resetSink()
    }
  }

  it("propagates a sink's exception and restores the runtime context") {
    val failure = new IllegalStateException("sink failed")
    DevTools.setSink((_, _, _) => throw failure)
    try {
      val runtime = Runtime.confined()
      val caught = intercept[IllegalStateException] {
        runtime.run(DevTools.warn("warning"))
      }
      (caught eq failure) shouldBe true
      runtime.isEntered shouldBe false
      Runtime.current shouldBe Runtime.default
    } finally {
      DevTools.resetSink()
    }
  }
}
