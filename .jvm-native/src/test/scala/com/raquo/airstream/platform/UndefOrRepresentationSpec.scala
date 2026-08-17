package com.raquo.airstream.platform

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/** Proves the Native opaque type stores values directly instead of allocating Option-like wrappers. */
class UndefOrRepresentationSpec extends AnyFunSpec with Matchers {

  describe("a present reference") {

    it("is represented by the exact input object") {
      val reference = new Object
      val lifted: UndefOr[Object] = reference

      lifted.asInstanceOf[AnyRef] should be theSameInstanceAs reference
      lifted.get should be theSameInstanceAs reference
    }

    it("stays the exact transformed object after map") {
      val first = new Object
      val second = new Object
      val lifted: UndefOr[Object] = first
      val mapped = lifted.map(_ => second)

      mapped.asInstanceOf[AnyRef] should be theSameInstanceAs second
      mapped.get should be theSameInstanceAs second
    }
  }

  describe("the absent representation") {

    it("reuses one process-wide sentinel") {
      val first: UndefOr[Object] = undefined
      val second: UndefOr[String] = undefined

      first.asInstanceOf[AnyRef] should be theSameInstanceAs second.asInstanceOf[AnyRef]
    }

    it("is distinct from a legitimate present null") {
      def lift[A](value: A): UndefOr[A] = value

      val empty: UndefOr[String | Null] = undefined
      val present: UndefOr[String | Null] = lift[String | Null](null)

      empty.asInstanceOf[AnyRef] should not be theSameInstanceAs(present.asInstanceOf[AnyRef])
      present.isDefined shouldBe true
    }
  }
}
