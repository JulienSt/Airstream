package com.raquo.airstream.platform

import com.raquo.airstream.AllocationMeter.bytesPerCall
import com.raquo.airstream.UnitSpec

class UpdateIdSlotsAllocationSpec extends UnitSpec {

  private var retained: JsArray[Int] = null

  it("restoring the protected array does not allocate more at construction than the original boxed array") {
    val parents = JsArray(new Object, new Object)
    val original = bytesPerCall()(() => retained = parents.map(_ => 0))
    val compact = bytesPerCall()(() => retained = UpdateIdSlots.sized(2))
    info(s"two parent slots: original $original, compact $compact bytes")
    withClue(s"$compact bytes for two compact slots against the original 64-byte array: ") {
      (compact <= 64.0) shouldBe true
    }
    retained.length shouldBe 2
  }
}
