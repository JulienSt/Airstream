package com.raquo.airstream.platform

import com.raquo.airstream.AllocationMeter.bytesPerCall
import com.raquo.airstream.UnitSpec

/** The transaction engine looks up a transaction's queued children every time a transaction completes; on Scala.js that
  * is a native `Map.get`, and on the JVM and Native the lookup must not build an `Option` per call either.
  */
class JsMapAllocationSpec extends UnitSpec {

  it("looks up a present and an absent key without allocating") {
    val map = new JsMap[String, String]
    map.set("present", "value")
    val found = Array(false)
    val bytes = bytesPerCall() { () =>
      found(0) = map.get("present").isDefined && map.get("absent").isEmpty
    }
    withClue(s"$bytes bytes per pair of lookups: ") {
      (bytes < 1.0) shouldBe true
    }
    found(0) shouldBe true
  }
}
