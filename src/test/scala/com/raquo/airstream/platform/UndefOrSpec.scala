package com.raquo.airstream.platform

import scala.language.implicitConversions

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/** Pins down the allocation-free optional value Airstream's core is built on, for every platform it runs on.
  *
  * Airstream does not use `Option` in its hot paths. A pending-observable slot, a parent transaction, a cached value: each
  * of those is read on every event, and wrapping every one in a `Some` would allocate on every event. `js.UndefOr` is how
  * that is avoided on JavaScript, and the replacement has to avoid it the same way rather than being an `Option` behind a
  * different name.
  *
  * As with the array, this spec lives in the shared tree so the same assertions run against the JavaScript type and against
  * the replacement. The two share no code, so nothing else can show that they agree.
  *
  * The surface is what Airstream calls: an empty value, lifting a plain value into the type, fold, foreach, map, getOrElse,
  * and comparing against empty.
  */
class UndefOrSpec extends AnyFunSpec with Matchers {

    describe("the empty value") {

        it("reports itself as empty") {
            undefined.isEmpty shouldBe true
        }

        it("compares equal to itself, which is how the call sites test for it") {
            (undefined == undefined) shouldBe true
        }
    }

    describe("a lifted value") {

        it("reports itself as present") {
            val lifted: UndefOr[Int] = 42
            lifted.isEmpty shouldBe false
        }

        it("does not compare equal to the empty value") {
            val lifted: UndefOr[Int] = 42
            (lifted == undefined) shouldBe false
        }
    }

    describe("getOrElse") {

        it("hands back the value that is there") {
            val lifted: UndefOr[Int] = 7
            lifted.getOrElse(0) shouldBe 7
        }

        it("hands back the alternative when there is nothing") {
            val empty: UndefOr[Int] = undefined
            empty.getOrElse(99) shouldBe 99
        }

        it("does not evaluate the alternative when a value is present") {
            var evaluated = false
            val lifted: UndefOr[Int] = 5
            lifted.getOrElse({ evaluated = true; 0 }) shouldBe 5
            evaluated shouldBe false
        }
    }

    describe("map") {

        it("applies the function to a value that is there") {
            val lifted: UndefOr[Int] = 3
            lifted.map(_ * 2).getOrElse(0) shouldBe 6
        }

        it("leaves an empty value empty, without calling the function") {
            var called = false
            val empty: UndefOr[Int] = undefined
            empty.map(value => { called = true; value }).isEmpty shouldBe true
            called shouldBe false
        }
    }

    describe("foreach") {

        it("runs the body once for a value that is there") {
            var seen = 0
            val lifted: UndefOr[Int] = 4
            lifted.foreach(value => seen += value)
            seen shouldBe 4
        }

        it("does not run it at all for an empty value") {
            var seen = 0
            val empty: UndefOr[Int] = undefined
            empty.foreach(value => seen += value)
            seen shouldBe 0
        }
    }

    describe("fold") {

        it("uses the function when a value is there") {
            val lifted: UndefOr[Int] = 10
            lifted.fold(-1)(_ + 1) shouldBe 11
        }

        it("uses the fallback when there is nothing") {
            val empty: UndefOr[Int] = undefined
            empty.fold(-1)(_ + 1) shouldBe -1
        }
    }

    describe("reassignment, which is how the transaction slot is filled") {

        it("turns an empty slot into a filled one") {
            var slot: UndefOr[String] = undefined
            slot.isEmpty shouldBe true
            slot = "filled"
            slot.getOrElse("") shouldBe "filled"
        }

        it("can be emptied again") {
            var slot: UndefOr[String] = "filled"
            slot = undefined
            slot.isEmpty shouldBe true
        }
    }

    describe("nesting a value that is itself falsy in JavaScript terms") {

        it("keeps zero as a present value rather than treating it as absent") {
            val lifted: UndefOr[Int] = 0
            lifted.isEmpty shouldBe false
            lifted.getOrElse(-1) shouldBe 0
        }

        it("keeps the empty string as a present value") {
            val lifted: UndefOr[String] = ""
            lifted.isEmpty shouldBe false
            lifted.getOrElse("fallback") shouldBe ""
        }
    }
}
