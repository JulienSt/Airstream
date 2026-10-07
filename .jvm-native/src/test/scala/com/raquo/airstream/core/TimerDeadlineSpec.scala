package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.platform.RuntimeTimers

import scala.collection.mutable

/** Characterizes deadline invalidation and callback mutation before changing the idle pump. */
class TimerDeadlineSpec extends UnitSpec {

  it("notices an earlier deadline after idle pumps, cancellation and clock reset") {
    val timers = new RuntimeTimers(Runtime.default)
    val seen = mutable.Buffer.empty[String]
    val first = timers.setTimeout(10)(seen += "cancelled")
    timers.setTimeout(100)(seen += "late")
    timers.runDue(5) shouldBe 0
    timers.clearTimeout(first)
    timers.runDue(10) shouldBe 0
    timers.setTimeout(2)(seen += "early")
    timers.runDue(11) shouldBe 0
    timers.runDue(12) shouldBe 1
    seen.toList shouldBe List("early")
    timers.cancelAll()
    timers.setTimeout(1)(seen += "after cancel")
    timers.runDue(13) shouldBe 1
    timers.clearAll()
    timers.now() shouldBe 0.0
    timers.setTimeout(1)(seen += "after reset")
    timers.runDue(1) shouldBe 1
    seen.toList shouldBe List("early", "after cancel", "after reset")
    timers.pendingCount shouldBe 0
  }

  it("retains the remaining deadlines when a callback throws") {
    val timers = new RuntimeTimers(Runtime.default)
    val failure = new IllegalStateException("timer callback")
    val seen = mutable.Buffer.empty[String]
    timers.setTimeout(5) {
      timers.setTimeout(0)(seen += "appended")
      throw failure
    }
    timers.setTimeout(10)(seen += "remaining")
    intercept[IllegalStateException](timers.runDue(20)) shouldBe failure
    timers.pendingCount shouldBe 2
    timers.runDue(20) shouldBe 2
    seen.toList shouldBe List("remaining", "appended")
    timers.pendingCount shouldBe 0
  }

  it("runs a zero-period interval once per pump and defers appended callbacks") {
    val timers = new RuntimeTimers(Runtime.default)
    val seen = mutable.Buffer.empty[String]
    val repeating = timers.setInterval(0) {
      seen += "tick"
      timers.setTimeout(0)(seen += "appended")
    }
    timers.runDue(0) shouldBe 1
    seen.toList shouldBe List("tick")
    timers.runDue(0) shouldBe 2
    seen.toList shouldBe List("tick", "tick", "appended")
    timers.clearInterval(repeating)
    timers.runDue(0) shouldBe 1
    seen.toList shouldBe List("tick", "tick", "appended", "appended")
    timers.pendingCount shouldBe 0
  }

  it("preserves comparison semantics for non-finite deadlines and a backwards clock") {
    val timers = new RuntimeTimers(Runtime.default)
    val seen = mutable.Buffer.empty[String]
    timers.setTimeout(Double.NaN)(seen += "nan")
    timers.setTimeout(Double.PositiveInfinity)(seen += "infinity")
    timers.setTimeout(5)(seen += "finite")
    timers.runDue(Double.NaN) shouldBe 0
    timers.now().isNaN shouldBe true
    timers.runDue(-10) shouldBe 0
    timers.now() shouldBe -10.0
    timers.runDue(5) shouldBe 1
    timers.runDue(Double.PositiveInfinity) shouldBe 1
    seen.toList shouldBe List("finite", "infinity")
    timers.pendingCount shouldBe 1
    timers.clearAll()
  }
}
