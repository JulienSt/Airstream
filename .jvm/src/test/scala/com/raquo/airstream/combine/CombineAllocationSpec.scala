package com.raquo.airstream.combine

import com.raquo.airstream.AllocationMeter.bytesPerCall
import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.Signal
import com.raquo.airstream.platform.JsArray
import com.raquo.airstream.state.Val

import scala.util.{Success, Try}

/** A combine node computes its value once per event: what that allocates by construction is the array of parent values
  * its combinator takes and the `Success` it stores. Checking the parents for failures must capture no flag in
  * a closure, and building the values array must not go through a generic builder that grows and copies it.
  */
class CombineAllocationSpec extends UnitSpec {

  final private class Probe(parents: JsArray[Signal[Int]]) extends CombineSignalN[Int, Int](parents, _.length) {
    def sample(): Try[Int] = combinedValue
  }

  it("reads successful signal parents once without an intermediate Try array (80 bytes)") {
    val probe = new Probe(JsArray(Val(1), Val(2)))
    val results = new Array[Try[Int]](1)
    val bytes = bytesPerCall()(() => results(0) = probe.sample())
    info(s"signal combine: $bytes bytes per sample")
    withClue(s"$bytes bytes per signal combination against 80: ") {
      (bytes <= 80) shouldBe true
    }
    results(0) shouldBe Success(2)
  }

  it("combines two successful values allocating only the values array and the Success") {
    val trys = JsArray[Try[Int]](Success(1), Success(2))
    val combinator: JsArray[Int] => Int = values => values.length
    val results = new Array[Try[Int]](1)
    val bytes = bytesPerCall()(() => results(0) = CombineObservable.jsArrayCombinator(trys, combinator))
    // The JVM array of two values: its wrapper (16 bytes), the buffer (24) and the backing Object[2] (24); then the
    // Success (16).
    val ceiling = 16 + 24 + 24 + 16
    withClue(s"$bytes bytes per combination against $ceiling: ") {
      (bytes <= ceiling) shouldBe true
    }
    results(0) shouldBe Success(2)
  }
}
