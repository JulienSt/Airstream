package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.ownership.ManualOwner

import scala.collection.mutable

/** The custom source can change between no, one and multiple startup events on every lifetime. */
class CustomStreamStartBufferSpec extends UnitSpec {

  it("keeps deferral and exact order across empty, single and multiple start batches") {
    var nextValues = List.empty[Int]
    val source = EventStream.fromCustomSource[Int](
      start = (emit, _, _, _) => nextValues.foreach(emit),
      stop = _ => ()
    )
    Seq(List(1), List(2, 3, 4), Nil, List(5), Nil, List(6, 7, 8, 9), List(10)).foreach { values =>
      nextValues = values
      val owner = new ManualOwner
      val seen = mutable.Buffer.empty[Int]
      Transaction.onStart.shared {
        source.foreach(seen += _)(owner)
        seen.toList shouldBe Nil
      }
      seen.toList shouldBe values
      owner.killSubscriptions()
    }
  }
}
