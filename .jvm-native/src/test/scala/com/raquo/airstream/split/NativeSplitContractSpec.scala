package com.raquo.airstream.split

import com.raquo.airstream.fixtures.TestableOwner
import com.raquo.airstream.platform.{JsArray, JsVector, ScalaJsArray}
import com.raquo.airstream.state.Var
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.{immutable, mutable}

/** Native contracts for collection instances and the keyed child identity that terminal lists rely on. */
class NativeSplitContractSpec extends AnyFunSpec with Matchers {

  final private case class Row(id: String, value: Int)

  final private class RenderedRow(val id: String, val row: KeyedStrictSignal[String, Row])

  describe("Splittable instances used by Native") {

    it("provides the strict immutable collection shapes") {
      implicitly[Splittable[List]].create(Seq(1, 2)) shouldBe List(1, 2)
      implicitly[Splittable[Vector]].create(Seq(1, 2)) shouldBe Vector(1, 2)
      implicitly[Splittable[Set]].create(Seq(1, 2, 1)) shouldBe Set(1, 2)
      implicitly[Splittable[immutable.Seq]].create(Seq(1, 2)) shouldBe Seq(1, 2)
      implicitly[Splittable[collection.Seq]].create(Seq(1, 2)) shouldBe Seq(1, 2)
    }

    it("provides mutable Buffer and the platform array without changing their shapes") {
      val buffer = implicitly[Splittable[mutable.Buffer]].create(Seq(1, 2))
      buffer shouldBe mutable.Buffer(1, 2)

      val platformArray = implicitly[Splittable[JsArray]].create(Seq(1, 2))
      platformArray.length shouldBe 2
      platformArray(0) shouldBe 1
      platformArray(1) shouldBe 2
    }

    it("keeps the JavaScript-vector compatibility instance functional on Native") {
      val vector = Splittable.JsVectorSplittable.create(Seq(3, 1, 2))
      Splittable.JsVectorSplittable.map(vector, (value: Int) => value * 2).toSeq shouldBe Seq(6, 2, 4)
      Splittable.JsVectorSplittable.isEmpty(vector) shouldBe false
      Splittable.JsVectorSplittable.isEmpty(JsVector.empty[Int]) shouldBe true
    }

    it("keeps raw-array compatibility instances functional on Native") {
      val array = Splittable.ScalaJsArraySplittable.create(Seq(3, 1, 2))
      MutableSplittable.ScalaJsArrayMutableSplittable.updateAtIndex(array, 1, 9)

      array.toSeq shouldBe Seq(3, 9, 2)
      implicitly[Splittable[ScalaJsArray]].isEmpty(array) shouldBe false
      implicitly[MutableSplittable[ScalaJsArray]].getByIndex(array, 1) shouldBe 9
    }
  }

  describe("keyed child identity") {

    it("retains an unchanged child's object and signal when a sibling changes") {
      val owner = new TestableOwner
      val rows = Var(Vector(Row("a", 1), Row("b", 1)))
      val creations = mutable.Map.empty[String, Int].withDefaultValue(0)
      var latest = Vector.empty[RenderedRow]

      rows.signal
        .splitSeq(_.id) { child =>
          creations.update(child.key, creations(child.key) + 1)
          new RenderedRow(child.key, child)
        }
        .foreach(latest = _)(owner)

      val originalA = latest.find(_.id == "a").get
      val originalASignal = originalA.row
      rows.set(Vector(Row("a", 1), Row("b", 2)))
      val currentA = latest.find(_.id == "a").get

      (currentA eq originalA) shouldBe true
      (currentA.row eq originalASignal) shouldBe true
      currentA.row.now() shouldBe Row("a", 1)
      creations.toMap shouldBe Map("a" -> 1, "b" -> 1)
      owner.killSubscriptions()
    }

    it("reorders existing children without rebuilding their keyed objects") {
      val owner = new TestableOwner
      val rows = Var(Vector(Row("a", 1), Row("b", 2), Row("c", 3)))
      var buildCount = 0
      var latest = Vector.empty[RenderedRow]

      rows.signal
        .splitSeq(_.id) { child =>
          buildCount += 1
          new RenderedRow(child.key, child)
        }
        .foreach(latest = _)(owner)

      val originalById = latest.map(row => row.id -> row).toMap
      rows.set(Vector(Row("c", 3), Row("a", 1), Row("b", 2)))

      latest.map(_.id) shouldBe Vector("c", "a", "b")
      latest.foreach(row => (row eq originalById(row.id)) shouldBe true)
      buildCount shouldBe 3
      owner.killSubscriptions()
    }
  }
}
