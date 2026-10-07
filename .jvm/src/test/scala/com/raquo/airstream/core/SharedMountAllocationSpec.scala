package com.raquo.airstream.core

import com.raquo.airstream.{AllocationMeter, UnitSpec}
import com.raquo.airstream.ownership.ManualOwner

/** Includes the full shared mount and cleanup, rather than measuring an unused source constructor. */
class SharedMountAllocationSpec extends UnitSpec {

  it("allocates at most 850 bytes per source in a complete shared mount") {
    var sum = 0
    val mount: () => Unit = () => {
      sum = 0
      val owner = new ManualOwner
      Transaction.onStart.shared {
        var index = 0
        while (index < 100) {
          EventStream.fromValue(index).foreach(value => sum += value)(owner)
          index += 1
        }
      }
      owner.killSubscriptions()
    }
    val bytes = AllocationMeter.bytesPerCall(calls = 100, warmup = 20)(mount) / 100
    sum shouldBe 4950
    withClue(s"complete shared mount: $bytes bytes/source: ") {
      (bytes <= 850.0) shouldBe true
    }
  }
}
