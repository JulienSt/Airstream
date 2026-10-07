package com.raquo.airstream.combine

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.{AirstreamError, Signal}
import com.raquo.airstream.platform.JsArray
import com.raquo.airstream.state.{Val, Var}

import scala.util.{Failure, Success, Try}

/** Characterizations of the existing error shape and caller-owned result arrays, plus exactly-once parent reads. */
class CombineSignalEvaluationSpec extends UnitSpec {

  final private class Counted(value: Try[Int]) extends Val[Int](value) {
    var reads = 0
    override def tryNow(): Try[Int] = {
      reads += 1
      super.tryNow()
    }
  }

  final private class Probe[A](parents: JsArray[Signal[Int]], combine: JsArray[Int] => A)
  extends CombineSignalN[Int, A](parents, combine) {
    def sample(): Try[A] = combinedValue
  }

  List(List(1), List(0, 2), List(0, 1, 2)).foreach { failed =>
    it(s"preserves CombinedError positions for failed parents $failed and reads every parent once") {
      val errors = (0 until 3).map(index => new Exception(s"parent $index"))
      val parents = (0 until 3).map { index =>
        new Counted(if (failed.contains(index)) Failure(errors(index)) else Success(index))
      }
      var combinations = 0
      val probe = new Probe(JsArray[Signal[Int]](parents: _*), values => {
        combinations += 1
        values.length
      })
      probe.sample() shouldBe Failure(AirstreamError.CombinedError(
        (0 until 3).map(index => if (failed.contains(index)) Some(errors(index)) else None)
      ))
      parents.map(_.reads).toList shouldBe List(1, 1, 1)
      combinations shouldBe 0
    }
  }

  it("reads successful parents exactly once in order and calls the combinator once") {
    val first = new Counted(Success(5))
    val second = new Counted(Success(8))
    var combinations = 0
    val probe = new Probe(JsArray(first, second), values => {
      combinations += 1
      values(0) * 10 + values(1)
    })
    probe.sample() shouldBe Success(58)
    List(first.reads, second.reads) shouldBe List(1, 1)
    combinations shouldBe 1
  }

  it("never reuses an array retained by the user combinator") {
    val first = Var(1)
    val second = Var(2)
    val probe = new Probe(JsArray(first.signal, second.signal), values => values)
    val retained = probe.sample().get
    first.set(10)
    val next = probe.sample().get
    List(retained(0), retained(1)) shouldBe List(1, 2)
    List(next(0), next(1)) shouldBe List(10, 2)
    retained.push(99)
    next.length shouldBe 2
  }

  it("supports empty parents without invoking the error path") {
    val probe = new Probe(JsArray[Signal[Int]](), _.length)
    probe.sample() shouldBe Success(0)
  }
}
