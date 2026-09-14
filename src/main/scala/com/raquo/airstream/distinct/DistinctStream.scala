package com.raquo.airstream.distinct

import com.raquo.airstream.common.{InternalTryObserver, SingleParentStream}
import com.raquo.airstream.core.{EventStream, Protected, Transaction}
import com.raquo.airstream.platform.{undefined, UndefOr}

import scala.util.Try

/** Emits only values that are distinct from the last emitted value, according to isSame function */
class DistinctStream[A](
  override protected val parent: EventStream[A],
  isSame: (Try[A], Try[A]) => Boolean,
  resetOnStop: Boolean
) extends SingleParentStream[A, A] with InternalTryObserver[A] {

  override protected val topoRank: Int = Protected.topoRank(parent) + 1

  private var maybeLastSeenValue: UndefOr[Try[A]] = undefined

  override protected def onTry(nextValue: Try[A], transaction: Transaction): Unit = {
    val isDistinct = maybeLastSeenValue.map(!isSame(_, nextValue)).getOrElse(true)
    maybeLastSeenValue = nextValue
    if (isDistinct) {
      fireTry(nextValue, transaction)
    }
  }

  override protected def onStop(): Unit = {
    if (resetOnStop) {
      maybeLastSeenValue = undefined
    }
    super.onStop()
  }
}
