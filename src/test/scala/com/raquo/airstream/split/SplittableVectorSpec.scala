package com.raquo.airstream.split

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/** Pins the portable Vector instance that replaces no JavaScript type and therefore belongs in shared code. */
class SplittableVectorSpec extends AnyFunSpec with Matchers {

  private val vectorSplittable = implicitly[Splittable[Vector]]

  describe("the shared Vector instance") {

    it("creates a Vector in source order") {
      vectorSplittable.create(Seq(3, 1, 2)) shouldBe Vector(3, 1, 2)
    }

    it("maps every value while preserving the Vector shape") {
      vectorSplittable.map(Vector(1, 2, 3), (value: Int) => value * 2) shouldBe Vector(2, 4, 6)
    }

    it("updates only the first matching value") {
      vectorSplittable.findUpdate(Vector(1, 2, 2, 3), (value: Int) => value == 2, 9) shouldBe Vector(1, 9, 2, 3)
    }

    it("retains ordered indexes and empty detection") {
      vectorSplittable.zipWithIndex(Vector("a", "b")) shouldBe Vector("a" -> 0, "b" -> 1)
      vectorSplittable.isEmpty(Vector.empty[Int]) shouldBe true
      vectorSplittable.isEmpty(Vector(1)) shouldBe false
    }
  }
}
