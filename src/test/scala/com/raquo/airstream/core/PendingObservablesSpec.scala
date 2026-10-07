package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import org.scalacheck.{Gen, Prop, Test}

/** Characterize the queue contract independently of its array layout, including old custom nodes without the mixin. */
class PendingObservablesSpec extends UnitSpec {

  private class LegacyNode(val id: Int, rank: Int) extends WritableStream[Int] with SyncObservable[Int] {
    override protected val topoRank: Int = rank
    override protected def onWillStart(): Unit = ()
    override private[airstream] def syncFire(transaction: Transaction): Unit = ()
  }

  final private class Node(id: Int, rank: Int) extends LegacyNode(id, rank) with PendingState

  private def check(property: Prop): Unit = {
    val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(300), property)
    withClue(result.toString) { result.passed shouldBe true }
  }

  it("matches stable List.sortBy for equal ranks, growth, and a second drain") {
    check(Prop.forAll(Gen.choose(1, 100).flatMap(n => Gen.listOfN(n, Gen.choose(0, 12)))) { ranks =>
      val nodes = ranks.zipWithIndex.map { case (rank, id) => new Node(id, rank) }
      val queue = new PendingObservables
      val expected = nodes.zip(ranks).sortBy(_._2).map(_._1)
      (1 to 2).foreach { _ =>
        nodes.foreach(queue.enqueue)
        nodes.foreach(node => queue.contains(node) shouldBe true)
        expected.foreach { node =>
          (queue.dequeue() eq node) shouldBe true
          queue.contains(node) shouldBe false
        }
        queue.nonEmpty shouldBe false
      }
      true
    })
  }

  it("matches a stable list across enqueue, dequeue, clear, compaction, and legacy membership") {
    val commands = Gen.listOfN(300, Gen.choose(0, 70))
    check(Prop.forAll(commands) { commands =>
      val nodes = (0 until 64).map { id =>
        if (id % 2 == 0) new Node(id, id % 7) else new LegacyNode(id, id % 7)
      }
      val queue = new PendingObservables
      var model = List.empty[LegacyNode]
      commands.foreach { command =>
        if (command < nodes.length) {
          val node = nodes(command)
          if (!model.exists(_ eq node)) {
            queue.enqueue(node)
            model = (model :+ node).sortBy(_.id % 7)
          }
        } else if (command == 70) {
          queue.clear()
          model = Nil
        } else if (model.nonEmpty) {
          (queue.dequeue() eq model.head) shouldBe true
          model = model.tail
        }
        queue.nonEmpty shouldBe model.nonEmpty
        nodes.foreach(node => queue.contains(node) shouldBe model.exists(_ eq node))
      }
      queue.clear()
      nodes.foreach(node => queue.contains(node) shouldBe false)
      true
    })
  }
}
