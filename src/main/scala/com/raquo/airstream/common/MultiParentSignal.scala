package com.raquo.airstream.common

import com.raquo.airstream.core.{Protected, Signal, WritableSignal}
import com.raquo.airstream.platform.{JsArray, UpdateIdSlots}

/** A simple signal that has multiple parents. */
trait MultiParentSignal[I, O] extends WritableSignal[O] {

  /** This array is read-only, never update it. */
  protected val parents: JsArray[Signal[I]]

  /** The update id last seen from each parent, by the parent's index; see [[UpdateIdSlots]] for why it is unboxed. */
  protected lazy val _parentLastUpdateIds: JsArray[Int] = UpdateIdSlots.capture(parents)(Protected.lastUpdateId(_))

  override protected def onWillStart(): Unit = {
    parents.forEach(Protected.maybeWillStart(_))
    val shouldPullFromParent = updateParentLastUpdateIds()
    if (shouldPullFromParent) {
      updateCurrentValueFromParent()
    }
  }

  /** @return Whether parent has emitted since last time we checked */
  protected def updateParentLastUpdateIds(): Boolean = {
    var parentHasUpdated = false
    parents.forEachWithIndex { (parent, ix) =>
      val newLastUpdateId = Protected.lastUpdateId(parent)
      val lastSeenParentUpdateId = UpdateIdSlots.seen(_parentLastUpdateIds, ix)
      if (newLastUpdateId != lastSeenParentUpdateId) {
        UpdateIdSlots.record(_parentLastUpdateIds, ix, newLastUpdateId)
        parentHasUpdated = true
      }
    }
    parentHasUpdated
  }

  protected def updateCurrentValueFromParent(): Unit = {
    val nextValue = currentValueFromParent()
    setCurrentValue(nextValue)
  }

}
