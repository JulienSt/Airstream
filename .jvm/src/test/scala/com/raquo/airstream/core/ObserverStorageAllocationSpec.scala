package com.raquo.airstream.core

import com.raquo.airstream.AllocationMeter.bytesPerCall
import com.raquo.airstream.UnitSpec

/** Constructing and inspecting an inactive source must not eagerly build two growable arrays. */
class ObserverStorageAllocationSpec extends UnitSpec {

  final private class Source extends WritableStream[Int] {
    override protected val topoRank: Int = 1
    override protected def onWillStart(): Unit = ()
    def hasNoObservers: Boolean = numAllObservers == 0
  }

  it("keeps an unobserved source within a 64-byte construction budget including empty-state reads") {
    var last: Source = null
    var empty = 0
    val bytes = bytesPerCall() { () =>
      last = new Source
      if (last.hasNoObservers) empty += 1
    }
    empty shouldBe 550
    last.hasNoObservers shouldBe true
    info(s"unobserved source construction and inspection: $bytes bytes per call")
    withClue(s"unobserved source construction and inspection: $bytes bytes per call: ") {
      (bytes <= 64.0) shouldBe true
    }
  }
}
