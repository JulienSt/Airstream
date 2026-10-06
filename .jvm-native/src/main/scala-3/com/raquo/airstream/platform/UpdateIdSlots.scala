package com.raquo.airstream.platform

/** The update id a multi-parent signal last saw from each of its parents, one slot per parent, for platforms without
  * JavaScript.
  *
  * Mutable because it is per-node state rewritten on every event the node receives, and a node is touched only by the
  * thread holding its runtime's permit, so nothing else ever reads a slot while it is written. Unboxed
  * because a generic array would box every id it stores, and ids leave the JVM's small-integer cache after a runtime's first
  * hundred updates: an `Integer` per parent per event (`FireAllocationSpec`). The array never escapes: the length is fixed
  * when the slots are made, and a slot is only read with `seen` and written with `record`.
  */
private[airstream] opaque type UpdateIdSlots = Array[Int]

private[airstream] object UpdateIdSlots {

  /** One slot per parent, each holding 0 until it is recorded. */
  def sized(length: Int): UpdateIdSlots = new Array[Int](length)

  extension (slots: UpdateIdSlots) {

    /** The update id last recorded for parent `index`. */
    inline def seen(index: Int): Int = slots(index)

    /** Remember `updateId` as the one last seen from parent `index`. */
    inline def record(index: Int, updateId: Int): Unit = slots(index) = updateId
  }
}
