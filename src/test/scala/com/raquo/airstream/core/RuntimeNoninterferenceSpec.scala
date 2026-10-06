package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.ownership.{ManualOwner, Subscription}
import com.raquo.airstream.state.Var
import org.scalacheck.{Gen, Prop, Test}

import scala.collection.mutable

/** On every platform, Scala.js included: noninterference under noise as a metamorphic property. A generated program runs on runtime A once alone and once interleaved, operation by operation in a
  * generated order, with another generated program on runtime B; both runtimes live on the calling thread, which is all
  * Scala.js has. A's projected trace (observer values and errors, custom-source starts and stops) must not change.
  */
class RuntimeNoninterferenceSpec extends UnitSpec {

  sealed trait Op

  object Op {
    final case class SetLeft(value: Int) extends Op
    final case class SetRight(value: Int) extends Op
    case object Increment extends Op
    final case class Fail(message: String) extends Op
    final case class Batch(left: Int, right: Int) extends Op
    final case class ObserveCombined(observer: Int) extends Op
    final case class ObserveMerged(observer: Int) extends Op
    final case class Kill(observer: Int) extends Op
    case object KillAll extends Op
  }

  private val genOp: Gen[Op] = Gen.frequency(
    3 -> Gen.choose(-3, 3).map(Op.SetLeft.apply),
    2 -> Gen.choose(-3, 3).map(Op.SetRight.apply),
    2 -> Gen.const(Op.Increment),
    1 -> Gen.oneOf("a", "b").map(Op.Fail.apply),
    1 -> Gen.zip(Gen.choose(-3, 3), Gen.choose(-3, 3)).map((Op.Batch.apply _).tupled),
    2 -> Gen.choose(0, 3).map(Op.ObserveCombined.apply),
    2 -> Gen.choose(0, 3).map(Op.ObserveMerged.apply),
    1 -> Gen.choose(0, 3).map(Op.Kill.apply),
    1 -> Gen.const(Op.KillAll)
  )

  private val genProgram: Gen[List[Op]] = Gen.choose(1, 25).flatMap(size => Gen.listOfN(size, genOp))

  /** One runtime's graph and the trace it leaves: two sources, a mapped and combined signal, a filtered change stream merged
    * with a custom source that records its starts and stops, observers keyed by number.
    */
  final private class Subject(val runtime: Runtime) {
    val trace: mutable.Buffer[String] = mutable.Buffer()
    private val owner = runtime.run(new ManualOwner)
    private val left = runtime.run(Var(0))
    private val right = runtime.run(Var(0))
    private val combined = runtime.run(left.signal.map(_ * 2).combineWith(right.signal))
    private val custom = runtime.run(
      EventStream.fromCustomSource[Int](
        start = (fire, _, startIndex, _) => {
          trace += s"start ${startIndex()}"
          fire(100 + startIndex())
        },
        stop = startIndex => trace += s"stop $startIndex"
      )
    )
    private val merged = runtime.run(EventStream.merge(left.signal.changes.filter(_ % 2 == 0), custom))
    private val attached = mutable.Map[Int, Subscription]()

    private def observer[A](id: Int): Observer[A] = Observer.fromTry[A] { value =>
      trace += s"observer $id ${value.fold(err => s"error ${err.getMessage}", _.toString)}"
    }

    def apply(op: Op): Unit = op match {
      case Op.SetLeft(value) => left.set(value)
      case Op.SetRight(value) => right.set(value)
      case Op.Increment => left.update(_ + 1)
      case Op.Fail(message) => left.setError(new Exception(message))
      case Op.Batch(l, r) => Var.set(left -> l, right -> r)
      case Op.ObserveCombined(id) =>
        if (!attached.contains(id)) attached(id) = combined.addObserver(observer(id))(owner)
      case Op.ObserveMerged(id) =>
        if (!attached.contains(id)) attached(id) = merged.addObserver(observer(id))(owner)
      case Op.Kill(id) =>
        attached.remove(id).foreach(_.kill())
      case Op.KillAll =>
        owner.killSubscriptions()
        attached.clear()
    }
  }

  /** Run `program` on a fresh runtime alone and return its trace. */
  private def alone(program: List[Op]): List[String] = {
    val subject = new Subject(Runtime.confined())
    program.foreach(subject.apply)
    subject.trace.toList
  }

  /** Run `program` on a fresh runtime A and `noise` on a fresh runtime B, taking the next operation from A when the schedule
    * says so and from B otherwise; return A's trace.
    */
  private def interleaved(program: List[Op], noise: List[Op], schedule: List[Boolean]): List[String] = {
    val a = new Subject(Runtime.confined())
    val b = new Subject(Runtime.confined())
    val (restA, restB) = schedule.foldLeft((program, noise)) {
      case ((op :: rest, others), true) =>
        a(op)
        (rest, others)
      case ((ops, op :: rest), false) =>
        b(op)
        (ops, rest)
      case (remaining, _) => remaining
    }
    restA.foreach(a.apply)
    restB.foreach(b.apply)
    a.trace.toList
  }

  private def silenced[A](body: => A): A = {
    val swallow: Throwable => Unit = _ => ()
    AirstreamError.registerUnhandledErrorCallback(swallow)
    AirstreamError.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
    try body
    finally {
      AirstreamError.registerUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
      AirstreamError.unregisterUnhandledErrorCallback(swallow)
    }
  }

  it("leaves a runtime's projected trace unchanged by another runtime's interleaved activity") {
    val property = Prop.forAll(genProgram, genProgram, Gen.listOfN(60, Gen.oneOf(true, false))) { (program, noise, schedule) =>
      val expected = alone(program)
      val actual = interleaved(program, noise, schedule)
      if (actual != expected) {
        println(s"program $program, noise $noise, schedule $schedule: alone $expected, interleaved $actual")
      }
      actual == expected
    }
    val result = silenced(Test.check(Test.Parameters.default.withMinSuccessfulTests(300), property))
    withClue(result.toString) {
      result.passed shouldBe true
    }
  }
}
