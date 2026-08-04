package com.raquo.airstream.platform

import org.scalacheck.{Gen, Prop, Test}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Pins down the growable array Airstream's core is built on, for every platform it runs on.
  *
  * This spec lives in the shared source tree on purpose. On Scala.js it exercises the JavaScript array Airstream has always
  * used; on Scala Native it exercises the replacement. Running the same assertions against both is the only way to know
  * that the replacement behaves like the thing it replaces — a claim that is otherwise impossible to check and easy to get
  * quietly wrong, since the two implementations share no code at all.
  *
  * The surface under test is the one Airstream actually calls: length, apply, update, push, shift, splice, indexOf, map,
  * forEach, and the JavaScript array's comma-separated string rendering. Nothing wider. An abstraction that copies a
  * foreign API in full leaves a second API to keep correct, and every operation added here has to be kept in agreement
  * across two implementations forever.
  */
class JsArraySpec extends AnyFunSpec with Matchers {

  private val intLists: Gen[List[Int]] =
    Gen.choose(0, 80).flatMap(size => Gen.listOfN(size, Gen.choose(-1000, 1000)))

  private def lift[A](value: A): UndefOr[A] = value

  private def checkProperty(property: Prop): Unit = {
    val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(200), property)
    withClue(result.toString) {
      result.passed shouldBe true
    }
  }

  /** Read an array's contents out through the surface under test, so the assertions do not depend on a richer API than
    * the one both platforms actually agree on.
    */
  private def contentsOf[A](array: JsArray[A]): List[A] =
    (0 until array.length).map(array.apply).toList

  describe("an empty array") {

    it("reports zero length") {
      JsArray[Int]().length shouldBe 0
    }

    it("holds nothing to read back") {
      contentsOf(JsArray[Int]()) shouldBe Nil
    }
  }

  describe("push") {

    it("appends at the end, in the order the elements arrived") {
      val array = JsArray[Int]()
      array.push(1)
      array.push(2)
      contentsOf(array) shouldBe List(1, 2)
    }

    it("grows the length by one each time") {
      val array = JsArray[Int]()
      (1 to 5).foreach(value => array.push(value))
      array.length shouldBe 5
    }
  }

  describe("apply") {

    it("reads the element standing at that index") {
      JsArray(10, 20, 30).apply(1) shouldBe 20
    }

    it("reads the first and last positions, not only the middle") {
      val array = JsArray("a", "b", "c")
      array(0) shouldBe "a"
      array(2) shouldBe "c"
    }
  }

  it("returns the empty sentinel before the first and after the last valid position") {
    val empty = JsArray[UndefOr[String]]()
    empty(-1).isEmpty shouldBe true
    empty(0).isEmpty shouldBe true
  }

  it("does not grow when an out-of-bounds position is read") {
    val array = JsArray[UndefOr[Int]](lift(1))
    array(array.length).isEmpty shouldBe true
    array(100).isEmpty shouldBe true
    array.length shouldBe 1
  }

  describe("update") {

    it("replaces the element at that index") {
      val array = JsArray(1, 2, 3)
      array.update(1, 99)
      contentsOf(array) shouldBe List(1, 99, 3)
    }

    it("leaves the length alone") {
      val array = JsArray(1, 2, 3)
      array.update(0, 42)
      array.length shouldBe 3
    }
  }

  describe("shift") {

    it("removes and returns the first element") {
      val array = JsArray(7, 8, 9)
      array.shift() shouldBe 7
      contentsOf(array) shouldBe List(8, 9)
    }

    it("shortens the array by one") {
      val array = JsArray(1, 2)
      array.shift()
      array.length shouldBe 1
    }
  }

  it("returns the empty sentinel when the array is already empty") {
    JsArray[UndefOr[Int]]().shift().isEmpty shouldBe true
  }

  describe("splice") {

    it("removes the element at an index when asked to delete one") {
      val array = JsArray(1, 2, 3)
      array.splice(1, 1)
      contentsOf(array) shouldBe List(1, 3)
    }

    it("removes several when asked for several") {
      val array = JsArray(1, 2, 3, 4)
      array.splice(1, 2)
      contentsOf(array) shouldBe List(1, 4)
    }

    it("removes nothing when the delete count is zero, which is how it is used to insert") {
      val array = JsArray(1, 2, 4)
      array.splice(2, 0, 3)
      contentsOf(array) shouldBe List(1, 2, 3, 4)
    }
  }

  describe("indexOf") {

    it("finds the first position holding that element") {
      JsArray("a", "b", "a").indexOf("a") shouldBe 0
    }

    it("answers with minus one when the element is absent, rather than failing") {
      JsArray("a").indexOf("z") shouldBe -1
    }
  }

  describe("map") {

    it("applies the function to every element, in order") {
      contentsOf(JsArray(1, 2, 3).map(_ * 2)) shouldBe List(2, 4, 6)
    }

    it("leaves the array it was called on untouched") {
      val array = JsArray(1, 2)
      array.map(_ + 100)
      contentsOf(array) shouldBe List(1, 2)
    }
  }

  describe("mapWithIndex") {

    it("hands the function each element together with its position") {
      contentsOf(JsArray("a", "b", "c").mapWithIndex((value, index) => s"$index$value")) shouldBe
        List("0a", "1b", "2c")
    }

    it("leaves the array it was called on untouched") {
      val array = JsArray(1, 2)
      array.mapWithIndex((value, _) => value + 100)
      contentsOf(array) shouldBe List(1, 2)
    }
  }

  describe("forEachWithIndex") {

    it("visits every element with its position, in order") {
      val seen = List.newBuilder[String]
      JsArray("a", "b").forEachWithIndex((value, index) => seen += s"$index$value")
      seen.result() shouldBe List("0a", "1b")
    }

    it("visits nothing for an empty array") {
      val seen = List.newBuilder[String]
      JsArray[String]().forEachWithIndex((value, index) => seen += s"$index$value")
      seen.result() shouldBe Nil
    }
  }

  describe("unshift") {

    it("inserts at the front, ahead of everything already there") {
      val array = JsArray(2, 3)
      array.unshift(1)
      contentsOf(array) shouldBe List(1, 2, 3)
    }

    it("grows the array by one") {
      val array = JsArray(2)
      array.unshift(1)
      array.length shouldBe 2
    }
  }

  describe("setting the length") {

    it("truncates to that many elements, which is how the core clears a buffer") {
      val array = JsArray(1, 2, 3)
      array.length = 0
      contentsOf(array) shouldBe Nil
    }

    it("keeps the elements before the new length") {
      val array = JsArray(1, 2, 3)
      array.length = 2
      contentsOf(array) shouldBe List(1, 2)
    }
  }

  describe("string rendering") {

    it("joins several elements with commas like a JavaScript array") {
      JsArray("a", "b", "c").toString shouldBe "a,b,c"
    }

    it("renders empty and single-element arrays without collection wrappers") {
      JsArray[String]().toString shouldBe ""
      JsArray("only").toString shouldBe "only"
    }
  }

  describe("forEach") {

    it("visits every element once, in order") {
      val seen = List.newBuilder[Int]
      JsArray(1, 2, 3).forEach(value => seen += value)
      seen.result() shouldBe List(1, 2, 3)
    }

    it("visits nothing at all for an empty array") {
      val seen = List.newBuilder[Int]
      JsArray[Int]().forEach(value => seen += value)
      seen.result() shouldBe Nil
    }
  }

  describe("mutation through a shared reference") {

    it("is visible to every holder, because this is a mutable structure by design") {
      val array = JsArray(1)
      val alias = array
      alias.push(2)
      contentsOf(array) shouldBe List(1, 2)
    }

    it("does not leak into a mapped copy, which is a new array") {
      val array = JsArray(1)
      val mapped = array.map(identity)
      array.push(2)
      mapped.length shouldBe 1
    }
  }

  describe("properties against the Scala collections reference") {

    it("agrees on construction, indexed reads, indexOf, map, and ordered iteration") {
      checkProperty(Prop.forAll(intLists) { values =>
        val actual = JsArray(values: _*)
        val probe = values.headOption.getOrElse(2001)
        actual.length == values.length &&
        contentsOf(actual) == values &&
        actual.indexOf(probe) == values.indexOf(probe) &&
        contentsOf(actual.map(_ + 1)) == values.map(_ + 1)
      })
    }

    it("agrees after push, unshift, shift, splice, update, and truncation") {
      checkProperty(Prop.forAll(intLists) { values =>
        val actual = JsArray(values: _*)
        val reference = mutable.ArrayBuffer.from(values)
        actual.push(17)
        reference.append(17)
        actual.unshift(23)
        reference.prepend(23)
        actual.shift()
        reference.remove(0)
        val spliceIndex = reference.length / 2
        val deleteCount = math.min(2, reference.length - spliceIndex)
        actual.splice(spliceIndex, deleteCount, 31)
        reference.remove(spliceIndex, deleteCount)
        reference.insert(spliceIndex, 31)
        if (reference.nonEmpty) {
          actual.update(reference.length - 1, 47)
          reference.update(reference.length - 1, 47)
        }
        val targetLength = reference.length / 2
        actual.length = targetLength
        reference.dropRightInPlace(reference.length - targetLength)
        contentsOf(actual) == reference.toList
      })
    }
  }
}
