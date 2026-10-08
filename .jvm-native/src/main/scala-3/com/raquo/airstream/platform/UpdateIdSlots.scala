package com.raquo.airstream.platform

/** The update id a multi-parent signal last saw from each of its parents, one slot per parent, for platforms without
  * JavaScript.
  *
  * Mutable because it is per-node state rewritten on every event the node receives, and a node is touched only by the
  * thread holding its runtime's permit, so nothing else ever reads a slot while it is written. Unboxed
  * because a generic array would box every id it stores, and ids leave the JVM's small-integer cache after a runtime's first
  * hundred updates: an `Integer` per parent per event (`FireAllocationSpec`). The protected extension surface remains a
  * JsArray. Internal seen/record calls use primitive storage; an extension using the ordinary array surface materializes
  * the same live array as a boxed buffer, after which both access paths observe all mutations.
  */
private[airstream] object UpdateIdSlots {

  inline def capture[A](values: JsArray[A])(inline read: A => Int): JsArray[Int] = {
    val slots = sized(values.length)
    values.forEachWithIndex((value, ix) => record(slots, ix, read(value)))
    slots
  }

  /** One slot per parent, each holding 0 until it is recorded. */
  def sized(length: Int): JsArray[Int] = JsArray.intSlots(length)

  /** The update id last recorded for parent `index`. */
  @inline def seen(slots: JsArray[Int], index: Int): Int = JsArray.seenInt(slots, index)

  /** Remember `updateId` as the one last seen from parent `index`. */
  @inline def record(slots: JsArray[Int], index: Int, updateId: Int): Unit = JsArray.recordInt(slots, index, updateId)
}
