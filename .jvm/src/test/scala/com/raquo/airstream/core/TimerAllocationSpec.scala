package com.raquo.airstream.core

import com.raquo.airstream.{AllocationMeter, UnitSpec}
import com.raquo.airstream.platform.RuntimeTimers

/** A host polling before any timer is due must not allocate a filtered queue on every event-loop tick. */
class TimerAllocationSpec extends UnitSpec {

  it("allocates nothing for a warmed timer pump before the earliest deadline") {
    val timers = new RuntimeTimers(Runtime.default)
    var fired = 0
    (0 until 64).foreach(_ => timers.setTimeout(10000000)(fired += 1))
    var clock = 0.0
    val pump: () => Unit = () => {
      clock += 1
      timers.runDue(clock)
      ()
    }
    // Match the propagation benchmark's 20 * 200000 warm-up calls, then measure one million pumps.
    // A short 200000-call warm-up can still include JIT recompilation after the full suite changes call profiles.
    val bytes = AllocationMeter.bytesPerCall(calls = 1000000, warmup = 4000000)(pump)
    timers.now() shouldBe 5000000.0
    fired shouldBe 0
    timers.pendingCount shouldBe 64
    timers.runDue(10000000) shouldBe 64
    fired shouldBe 64
    timers.pendingCount shouldBe 0
    withClue(s"idle timer pump: $bytes bytes per call: ") {
      bytes shouldBe 0.0
    }
  }
}
