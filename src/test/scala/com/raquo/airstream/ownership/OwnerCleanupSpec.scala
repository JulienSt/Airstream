package com.raquo.airstream.ownership

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.Runtime
import org.scalacheck.{Gen, Prop, Test}

import scala.collection.mutable

/** An owner's cleanup does not miss resources a cleanup appends, and does not run a cleanup twice when a cleanup
  * kills another possession.
  *
  * The oracle is a small sequential model of the current `Owner` semantics, written independently of `JsResilientIterator`:
  * the owner kills its possessions in the order it acquired them; a possession a cleanup creates on the same owner is
  * appended and killed in the same pass; a possession a cleanup kills externally runs its own cleanup at that moment and is
  * skipped when the pass reaches it; after the pass the owner holds nothing, so a later pass sees only later possessions.
  * Generated cleanup programs are run against the real owner in a runtime of their own; the order of cleanups must match.
  */
class OwnerCleanupSpec extends UnitSpec {

  /** What a subscription's cleanup does besides recording that it ran. */
  sealed trait Action

  object Action {

    case object Nothing extends Action

    /** Create another subscription on the same owner, whose cleanup does `child`. */
    final case class Append(child: Action) extends Action

    /** Kill the subscription created `index`-th, if it exists and is alive. */
    final case class KillOther(index: Int) extends Action
  }

  private val genLeaf: Gen[Action] = Gen.frequency(
    2 -> Gen.const(Action.Nothing),
    1 -> Gen.choose(0, 9).map(Action.KillOther.apply)
  )

  private val genAction: Gen[Action] = Gen.frequency(
    3 -> genLeaf,
    2 -> genLeaf.map(Action.Append.apply),
    1 -> genLeaf.map(Action.Append.apply).map(Action.Append.apply)
  )

  private val genPrograms: Gen[List[Action]] = Gen.choose(1, 6).flatMap(size => Gen.listOfN(size, genAction))

  /** The sequential model: the order in which cleanups run during one owner pass over `initial`. */
  private def modelOrder(initial: List[Action]): List[Int] = {
    val actions = mutable.ArrayBuffer[Action](initial: _*)
    val order = mutable.ArrayBuffer[Int](initial.indices: _*)
    val killed = mutable.Set[Int]()
    val log = mutable.ArrayBuffer[Int]()

    def kill(index: Int): Unit = {
      killed += index
      log += index
      actions(index) match {
        case Action.Nothing => ()
        case Action.Append(child) =>
          actions += child
          order += actions.length - 1
        case Action.KillOther(other) =>
          if (other < actions.length && !killed.contains(other)) {
            kill(other)
          }
      }
    }

    var position = 0
    while (position < order.length) {
      val index = order(position)
      if (!killed.contains(index)) {
        kill(index)
      }
      position += 1
    }
    log.toList
  }

  /** The same pass on a real owner: the order cleanups ran in, and how often each ran. */
  private def airstreamOrder(initial: List[Action]): (List[Int], Map[Int, Int]) = {
    val runtime = Runtime.confined()
    runtime.run {
      val owner = new ManualOwner
      val subscriptions = mutable.ArrayBuffer[Subscription]()
      val log = mutable.ArrayBuffer[Int]()

      def create(action: Action): Unit = {
        val index = subscriptions.length
        subscriptions += null
        subscriptions(index) = new Subscription(
          owner,
          () => {
            log += index
            action match {
              case Action.Nothing => ()
              case Action.Append(child) => create(child)
              case Action.KillOther(other) =>
                if (other < subscriptions.length && !subscriptions(other).isKilled) {
                  subscriptions(other).kill()
                }
            }
          }
        )
      }

      initial.foreach(create)
      owner.killSubscriptions()
      val firstPass = log.toList
      val created = subscriptions.length

      // A later pass sees only what was created after the first one.
      create(Action.Nothing)
      owner.killSubscriptions()
      log.drop(firstPass.length).toList shouldBe List(created)

      (firstPass, log.groupBy(identity).view.mapValues(_.length).toMap)
    }
  }

  private def checkProperty(property: Prop): Unit = {
    val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(300), property)
    withClue(result.toString) {
      result.passed shouldBe true
    }
  }

  it("kills appended possessions in the same pass and runs every cleanup once, in the model's order") {
    checkProperty(Prop.forAll(genPrograms) { programs =>
      val expected = modelOrder(programs)
      val (actual, counts) = airstreamOrder(programs)
      val agrees = actual == expected && counts.values.forall(_ == 1)
      if (!agrees) {
        println(s"programs $programs: model $expected, airstream $actual, counts $counts")
      }
      agrees
    })
  }

  it("a cleanup that appends a possession which kills an earlier one leaves nothing behind") {
    val programs = List(Action.Nothing, Action.Append(Action.KillOther(0)), Action.Append(Action.KillOther(3)))
    val (actual, counts) = airstreamOrder(programs)
    actual shouldBe modelOrder(programs)
    actual shouldBe List(0, 1, 2, 3, 4)
    counts.values.toSet shouldBe Set(1)
  }
}
