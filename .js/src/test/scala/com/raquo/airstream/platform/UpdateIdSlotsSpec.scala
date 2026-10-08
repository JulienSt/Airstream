package com.raquo.airstream.platform

import com.raquo.airstream.UnitSpec

import scala.scalajs.js

class UpdateIdSlotsSpec extends UnitSpec {

  it("allocates fresh dense zero-filled arrays and preserves the native shared view") {
    List(-1, 0, 1, 7, 64).foreach { requested =>
      val slots = UpdateIdSlots.sized(requested)
      val length = math.max(0, requested)
      val native = slots.asInstanceOf[js.Array[Int]]
      slots.length shouldBe length
      js.Array.isArray(slots) shouldBe true
      // Reading an absent native slot as Int would coerce undefined to zero: require own indices as well.
      js.Object.keys(native).toList shouldBe (0 until length).map(_.toString).toList
      (slots eq UpdateIdSlots.sized(requested)) shouldBe false
      (0 until length).foreach { index =>
        UpdateIdSlots.seen(slots, index) shouldBe 0
      }
      if (length > 0) {
        UpdateIdSlots.record(slots, length - 1, 513)
        native(length - 1) shouldBe 513
        native(0) = 1025
        UpdateIdSlots.seen(slots, 0) shouldBe 1025
      }
    }
  }
}
