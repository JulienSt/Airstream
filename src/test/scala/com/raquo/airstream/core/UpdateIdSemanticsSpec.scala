package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import scala.collection.mutable

/** Update ids keep their comparison semantics now that each runtime counts its own. A child signal compares the id its parent's last update carried with the one it saw last, so
  * what matters is that restart, distinct, combine, switch and split read the same values whatever number the counter
  * stands at, including when it wraps from `Int.MaxValue` to `1`, and that another runtime's traffic never moves it.
  *
  * Each scenario is run once from a fresh counter and once from every start in the last stretch before the wrap, so the
  * wrap falls between every pair of its updates; the traces must be equal. No universal monotonicity is assumed: the
  * counter wraps exactly as it did before runtimes existed (`Signal.nextUpdateId` upstream), which the last case pins.
  */
class UpdateIdSemanticsSpec extends UnitSpec {

  /** Starts that put the wrap in front of, between and after each scenario's updates. */
  private val nearWrap: Seq[Int] = (Int.MaxValue - 24) to Int.MaxValue

  /** Run `scenario` in a fresh confined runtime whose next update id follows `lastId`. */
  private def traceFrom(lastId: Int)(scenario: () => Seq[String]): Seq[String] = {
    val runtime = Runtime.confined()
    runtime.run {
      runtime.continueUpdateIdsAfter(lastId)
      scenario()
    }
  }

  private def sameTraceAcrossTheWrap(scenario: () => Seq[String]): Unit = {
    val reference = traceFrom(0)(scenario)
    reference.nonEmpty shouldBe true
    nearWrap.foreach { start =>
      withClue(s"counter continuing after $start: ") {
        traceFrom(start)(scenario) shouldBe reference
      }
    }
  }

  private val restart: () => Seq[String] = () => {
    val log = mutable.Buffer[String]()
    val owner = new ManualOwner
    val source = Var(1)
    val mapped = source.signal.map(_ * 10)
    val updates = source.signal.updates
    def observe(): Unit = {
      mapped.foreach(value => log += s"mapped $value")(owner)
      updates.foreach(value => log += s"updates $value")(owner)
    }
    observe()
    source.set(2)
    owner.killSubscriptions()
    source.set(3)
    observe() // restart after a missed update: both re-sync
    owner.killSubscriptions()
    observe() // restart without an update: neither re-syncs
    source.set(4)
    owner.killSubscriptions()
    log.toList
  }

  private val distinctAndCombine: () => Seq[String] = () => {
    val log = mutable.Buffer[String]()
    val owner = new ManualOwner
    val left = Var(1)
    val right = Var(10)
    val distinct = left.signal.distinct
    val combined = distinct.combineWith(right.signal)
    combined.foreach(value => log += s"combined $value")(owner)
    left.set(1)
    left.set(2)
    owner.killSubscriptions()
    right.set(20)
    combined.foreach(value => log += s"combined again $value")(owner)
    left.set(2)
    right.set(30)
    owner.killSubscriptions()
    log.toList
  }

  private val switching: () => Seq[String] = () => {
    val log = mutable.Buffer[String]()
    val owner = new ManualOwner
    val first = Var(1)
    val second = Var(100)
    val choice = Var(true)
    val switched = choice.signal.flatMapSwitch(useFirst => if (useFirst) first.signal else second.signal)
    switched.foreach(value => log += s"switched $value")(owner)
    first.set(2)
    choice.set(false)
    first.set(3)
    second.set(200)
    owner.killSubscriptions()
    second.set(300)
    switched.foreach(value => log += s"switched again $value")(owner)
    choice.set(true)
    owner.killSubscriptions()
    log.toList
  }

  private final case class Item(id: Int, label: String)

  private val splitActive: () => Seq[String] = () => {
    val log = mutable.Buffer[String]()
    val owner = new ManualOwner
    val items = Var(List(Item(1, "a"), Item(2, "b")))
    val split = items.signal.splitSeq(_.id) { item => item.map(current => s"${item.key}:${current.label}") }
    split.flatMapSwitch(children => Signal.combineSeq(children)).foreach(labels => log += labels.mkString(","))(owner)
    items.set(List(Item(1, "a"), Item(2, "c")))
    items.set(List(Item(2, "c"), Item(3, "d")))
    owner.killSubscriptions()
    items.set(List(Item(3, "e")))
    split.flatMapSwitch(children => Signal.combineSeq(children)).foreach(labels => log += s"again ${labels.mkString(",")}")(owner)
    owner.killSubscriptions()
    log.toList
  }

  it("restart reads the same missed and unmissed updates whatever the counter stands at, across the wrap") {
    sameTraceAcrossTheWrap(restart)
  }

  it("distinct and combine read the same values across the wrap, including after a restart") {
    sameTraceAcrossTheWrap(distinctAndCombine)
  }

  it("a switched signal re-syncs the same inner values across the wrap") {
    sameTraceAcrossTheWrap(switching)
  }

  it("split delivers the same children and labels across the wrap while it is observed") {
    sameTraceAcrossTheWrap(splitActive)
  }

  it("another runtime's updates never advance this runtime's ids, however they interleave") {
    implicit val protectedAccess: Protected = Protected.protectedAccessEvidence
    def idsOf(noise: Int): Seq[Int] = {
      val a = Runtime.confined()
      val b = Runtime.confined()
      val va = a.run(Var(0))
      val vb = b.run(Var(0))
      (1 to 5).map { step =>
        (1 to noise).foreach(n => vb.set(n))
        va.set(step)
        Protected.lastUpdateId(va.signal)
      }
    }
    idsOf(noise = 0) shouldBe (1 to 5)
    idsOf(noise = 7) shouldBe (1 to 5)
  }

  it("wraps from Int.MaxValue to 1, never handing out 0, the initial-value marker, exactly as the process-wide counter did") {
    implicit val protectedAccess: Protected = Protected.protectedAccessEvidence
    val runtime = Runtime.confined()
    val source = runtime.run {
      runtime.continueUpdateIdsAfter(Int.MaxValue - 1)
      Var(0)
    }
    val ids = (1 to 3).map { value =>
      source.set(value)
      Protected.lastUpdateId(source.signal)
    }
    ids shouldBe Seq(Int.MaxValue, 1, 2)
  }
}
