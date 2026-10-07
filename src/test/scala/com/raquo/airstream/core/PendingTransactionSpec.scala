package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.combine.MergeStream
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.platform.JsArray

import scala.collection.mutable

class PendingTransactionSpec extends UnitSpec {

  final private class Node(rank: Int = 1, fire: Transaction => Unit = _ => ())
  extends WritableStream[Int] with SyncObservable[Int] with PendingState {
    override protected val topoRank: Int = rank
    override protected def onWillStart(): Unit = ()
    override private[airstream] def syncFire(transaction: Transaction): Unit = fire(transaction)
    def emit(value: Int, transaction: Transaction): Unit = fireValue(value, transaction)
  }

  it("rejects a completed transaction before it can poison the engine queue") {
    val runtime = Runtime.confined()
    try runtime.run {
        val completed = new Transaction(_ => ())
        val node = new Node
        intercept[IllegalStateException] {
          completed.enqueuePendingObservable(node)
        }
        node.isPending shouldBe false
        Transaction.isClearState shouldBe true
      }
    finally runtime.close()
  }

  it("checks transaction identity even when a custom transaction overrides equals") {
    val runtime = Runtime.confined()
    try runtime.run {
        val completed = new Transaction(_ => ())
        val node = new Node
        var rejected = false
        new Transaction(_ => {
          try completed.enqueuePendingObservable(node)
          catch { case _: IllegalStateException => rejected = true }
        }) {
          override def equals(other: Any): Boolean = other.isInstanceOf[Transaction]
          override def hashCode(): Int = 1
        }
        rejected shouldBe true
        node.isPending shouldBe false
      }
    finally runtime.close()
  }

  it("clears abandoned flags after syncFire throws, then admits the same nodes in the next transaction") {
    val runtime = Runtime.confined()
    try runtime.run {
        val errors = mutable.Buffer.empty[Throwable]
        val seen = mutable.Buffer.empty[Int]
        val failure = new Exception("syncFire failed")
        runtime.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
        runtime.registerUnhandledErrorCallback(errors += _)
        var fail = true
        val first = new Node(1, _ => if (fail) throw failure else seen += 1)
        val second = new Node(2, _ => seen += 2)
        Transaction { transaction =>
          transaction.enqueuePendingObservable(second)
          transaction.enqueuePendingObservable(first)
        }
        errors.toList shouldBe List(failure)
        seen.toList shouldBe Nil
        first.isPending shouldBe false
        second.isPending shouldBe false
        Transaction.isClearState shouldBe true
        fail = false
        Transaction { transaction =>
          transaction.containsPendingObservable(first) shouldBe false
          transaction.containsPendingObservable(second) shouldBe false
          transaction.enqueuePendingObservable(second)
          transaction.enqueuePendingObservable(first)
        }
        seen.toList shouldBe List(1, 2)
        Transaction.isClearState shouldBe true
      }
    finally runtime.close()
  }

  it("clears remaining flags even when the unsafe error callback rethrows") {
    val runtime = Runtime.confined()
    try runtime.run {
        runtime.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
        runtime.registerUnhandledErrorCallback(AirstreamError.unsafeRethrowErrorCallback)
        val failure = new Exception("unsafe syncFire failure")
        val first = new Node(1, _ => throw failure)
        val second = new Node(2)
        val thrown = intercept[Exception] {
          Transaction { transaction =>
            transaction.enqueuePendingObservable(first)
            transaction.enqueuePendingObservable(second)
          }
        }
        (thrown eq failure) shouldBe true
        first.isPending shouldBe false
        second.isPending shouldBe false
        Transaction.isClearState shouldBe true
        Transaction(_.enqueuePendingObservable(second))
        second.isPending shouldBe false
      }
    finally runtime.close()
  }

  it("allows a MergeStream to re-enter the same pending queue after its first syncFire") {
    val runtime = Runtime.confined()
    try runtime.run {
        val owner = new ManualOwner
        try {
          val first = new Node
          val second = new Node
          val firings = mutable.Buffer.empty[Transaction]
          val values = mutable.Buffer.empty[Int]
          val merged = new MergeStream[Int](JsArray(first, second)) {
            override private[airstream] def syncFire(transaction: Transaction): Unit = {
              isPending shouldBe false
              firings += transaction
              super.syncFire(transaction)
            }
          }
          merged.foreach(values += _)(owner)
          val late = new Node(3, transaction => second.emit(2, transaction))
          Transaction { transaction =>
            first.emit(1, transaction)
            transaction.enqueuePendingObservable(late)
          }
          firings.length shouldBe 2
          (firings.head eq firings.last) shouldBe true
          values.toList shouldBe List(1, 2)
          merged.isPending shouldBe false
          Transaction.isClearState shouldBe true
        } finally owner.killSubscriptions()
      }
    finally runtime.close()
  }
}
