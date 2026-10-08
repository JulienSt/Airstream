package com.raquo.airstream.misc

import com.raquo.airstream.core.{Transaction, WritableStream}

/** A constant startup event needs no external-source configuration or asynchronous ingress callbacks. */
final private[airstream] class ValueStream[A](value: A, emitOnce: Boolean) extends WritableStream[A] {

  override protected val topoRank: Int = 1

  private var startIndex = 0

  override protected def onWillStart(): Unit = {
    // Match CustomSource's first-will-start semantics, including a start stopped before its shared callback runs.
    startIndex += 1
  }

  override protected def onStart(): Unit = {
    if (!emitOnce || startIndex == 1) {
      if (runtime.trxOnStartFix144 && runtime.sharedStart.isInSharedStart) {
        runtime.sharedStart.add { transaction =>
          if (isStarted) fireValue(value, transaction)
        }
      } else {
        Transaction(runtime)(fireValue(value, _))
      }
    }
    super.onStart()
  }
}
