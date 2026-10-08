package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.platform.JsArray

import scala.collection.mutable

class ObserverListSpec extends UnitSpec {

  it("removes the first identity occurrence even when the same observer also occupies the tail") {
    val first = new Object
    val middle = new Object
    val observers = new ObserverList(JsArray(first, middle, first))
    observers.removeObserverNow(first) shouldBe true
    observers.length shouldBe 2
    (observers(0) eq middle) shouldBe true
    (observers(1) eq first) shouldBe true
    observers.removeObserverNow(first) shouldBe true
    observers.length shouldBe 1
    (observers(0) eq middle) shouldBe true
    observers.removeObserverNow(first) shouldBe false
    observers.removeObserverNow(middle) shouldBe true
    observers.length shouldBe 0
    observers.removeObserverNow(middle) shouldBe false
    observers.push(first)
    (observers(0) eq first) shouldBe true
  }

  it("continues visiting observers appended during traversal") {
    val first = new Object
    val second = new Object
    val observers = new ObserverList(JsArray(first))
    val visited = mutable.Buffer.empty[Object]
    observers.foreach { observer =>
      visited += observer
      if (observer eq first) observers.push(second)
    }
    visited.toList shouldBe List(first, second)
  }
}
