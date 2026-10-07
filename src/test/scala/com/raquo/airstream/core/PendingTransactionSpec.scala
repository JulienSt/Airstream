package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec

class PendingTransactionSpec extends UnitSpec {

  final private class Node extends WritableStream[Int] with SyncObservable[Int] with PendingState {
    override protected val topoRank: Int = 1
    override protected def onWillStart(): Unit = ()
    override private[airstream] def syncFire(transaction: Transaction): Unit = ()
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
}
