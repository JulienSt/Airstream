package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.util.JsPriorityQueue
import org.scalacheck.{Gen, Prop, Test}

import scala.collection.mutable

/** Generated DAGs execute through the real TransactionEngine and syncFire boundary. The reference drains the previous
  * JsPriorityQueue; a second, direct DAG evaluation checks the final values independently of either scheduler.
  */
class PendingGraphSpec extends UnitSpec {

  final private class Node(val id: Int, parents: Vector[Node])
  extends WritableStream[Int] with SyncObservable[Int] with PendingState {
    val rank: Int = parents.foldLeft(0)((rank, parent) => rank max parent.rank) + 1
    override protected val topoRank: Int = rank
    override protected def onWillStart(): Unit = ()
    var value: Int = 0
    var children: Vector[Node] = Vector.empty

    def publish(next: Int, transaction: Transaction): Unit = {
      value = next
      fireValue(value, transaction)
      children.foreach { child =>
        if (!transaction.containsPendingObservable(child)) transaction.enqueuePendingObservable(child)
      }
    }

    override private[airstream] def syncFire(transaction: Transaction): Unit = {
      isPending shouldBe false
      publish(parents.map(_.value).sum + id, transaction)
    }
  }

  private val graphs: Gen[Vector[Vector[Int]]] = Gen.choose(4, 48).flatMap { size =>
    Gen.listOfN(size, Gen.listOfN(size, Gen.oneOf(true, false))).map { rows =>
      rows.zipWithIndex.map { case (row, id) =>
        row.take(id).zipWithIndex.collect { case (true, parent) => parent }.toVector
      }.toVector
    }
  }

  private def compare(parents: Vector[Vector[Int]]): Boolean = {
    val owner = new ManualOwner
    try {
      val actual = mutable.Buffer.empty[(Int, Int)]
      val nodes = parents.indices.foldLeft(Vector.empty[Node]) { (nodes, id) =>
        nodes :+ new Node(id, parents(id).map(nodes))
      }
      nodes.foreach { node =>
        node.children = nodes.filter(child => parents(child.id).contains(node.id))
        node.foreach(value => actual += ((node.id, value)))(owner)
      }
      val roots = nodes.filter(node => parents(node.id).isEmpty)
      val referenceValues = Array.fill(nodes.length)(0)
      val expected = mutable.Buffer.empty[(Int, Int)]
      val oldQueue = new JsPriorityQueue[Int](id => nodes(id).rank)
      def publish(id: Int, value: Int): Unit = {
        referenceValues(id) = value
        expected += ((id, value))
        nodes(id).children.foreach { child =>
          if (!oldQueue.contains(child.id)) oldQueue.enqueue(child.id)
        }
      }
      List(3, -7, 11).foreach { input =>
        actual.clear()
        expected.clear()
        roots.foreach(node => publish(node.id, input + node.id))
        while (oldQueue.nonEmpty) {
          val id = oldQueue.dequeue()
          publish(id, parents(id).map(referenceValues(_)).sum + id)
        }
        Transaction(transaction => roots.foreach(node => node.publish(input + node.id, transaction)))
        actual.toList shouldBe expected.toList
        actual.length shouldBe nodes.length
        val independent = parents.indices.foldLeft(Vector.empty[Int]) { (values, id) =>
          values :+ (if (parents(id).isEmpty) input + id else parents(id).map(values).sum + id)
        }
        nodes.map(_.value) shouldBe independent
        nodes.foreach(node => node.isPending shouldBe false)
        Transaction.isClearState shouldBe true
      }
      true
    } finally owner.killSubscriptions()
  }

  it("matches the old queue's complete cross-node traces on generated DAGs and repeated root turns") {
    val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(300), Prop.forAll(graphs)(compare))
    withClue(result.toString) { result.passed shouldBe true }
  }
}
