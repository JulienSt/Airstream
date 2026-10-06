package com.raquo.airstream.platform

import com.raquo.airstream.AllocationMeter.bytesPerCall
import com.raquo.airstream.UnitSpec

/** The optional value of the hot paths allocates nothing at its call sites: the transaction engine, the pending
  * slots and the signal caches call its combinators with lambdas that capture the engine, the transaction or the signal.
  * Passed to an ordinary method, each such lambda is an object per call, which only the JIT's escape analysis removes, and
  * only while the call site stays monomorphic. Inlined with the lambda, there is nothing to allocate.
  */
class UndefOrAllocationSpec extends UnitSpec {

  final private class Box(val n: Int)

  private val present: UndefOr[Box] = UndefOr(new Box(3))

  private val absent: UndefOr[Box] = undefined

  private val captured = new Box(4)

  private var lastInt: Int = 0

  private var lastFlag: Boolean = false

  private var lastBox: UndefOr[Box] = undefined

  private def allocatesNothing(name: String)(body: () => Unit): Unit = {
    val bytes = bytesPerCall()(body)
    withClue(s"$name allocated $bytes bytes per call: ") {
      (bytes < 1.0) shouldBe true
    }
  }

  it("fold, map, filter, foreach, getOrElse, exists, forall and contains with capturing arguments allocate nothing") {
    allocatesNothing("fold") { () => lastInt = present.fold(captured.n)(box => box.n + captured.n) }
    allocatesNothing("fold on absent") { () => lastInt = absent.fold(captured.n)(box => box.n + captured.n) }
    allocatesNothing("map") { () => lastBox = present.map(box => if (box.n > captured.n) box else captured) }
    allocatesNothing("filter") { () => lastBox = present.filter(box => box.n < captured.n) }
    allocatesNothing("foreach") { () => present.foreach(box => lastInt = box.n + captured.n) }
    allocatesNothing("getOrElse") { () => lastInt = absent.getOrElse(captured).n }
    allocatesNothing("exists") { () => lastFlag = present.exists(box => box.n < captured.n) }
    allocatesNothing("forall") { () => lastFlag = present.forall(box => box.n < captured.n) }
    allocatesNothing("contains") { () => lastFlag = present.contains(captured) }
  }

  it("assigning a plain value to a slot allocates nothing") {
    allocatesNothing("assignment") { () => lastBox = captured }
  }
}
