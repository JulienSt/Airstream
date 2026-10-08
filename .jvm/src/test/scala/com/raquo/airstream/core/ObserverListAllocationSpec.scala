package com.raquo.airstream.core

import com.raquo.airstream.AllocationMeter.bytesPerCall
import com.raquo.airstream.UnitSpec
import com.raquo.airstream.platform.JsArray

/** A warmed backing store needs no temporary collection when its last observer is removed. */
class ObserverListAllocationSpec extends UnitSpec {

  it("removes and restores the last observer without allocating") {
    val observer = new Object
    val observers = new ObserverList(JsArray(observer))
    var removals = 0
    val bytes = bytesPerCall() { () =>
      if (observers.removeObserverNow(observer)) removals += 1
      observers.push(observer)
    }
    removals shouldBe 550
    observers.length shouldBe 1
    (observers(0) eq observer) shouldBe true
    info(s"tail observer removal/restoration: $bytes bytes per call")
    withClue(s"tail observer removal/restoration allocated $bytes bytes per call: ") {
      (bytes < 1.0) shouldBe true
    }
  }
}
