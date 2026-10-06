package com.raquo.airstream.platform

/** The update id a multi-parent signal last saw from each of its parents, one slot per parent, on Scala.js with Scala 2.13.
  *
  * The Scala 3 builds make this an opaque type; Scala 2.13 has none, so it is a value class over the same JavaScript array,
  * which erases to the same signature and hides the array just as well. Mutable because it is per-node state rewritten on
  * every event the node receives, touched only under its runtime's permit; a JavaScript array holds
  * numbers unboxed already. The length is fixed when the slots are made, and a slot is only read with `seen` and written
  * with `record`.
  */
final private[airstream] class UpdateIdSlots private (private val slots: JsArray[Int]) extends AnyVal {

  /** The update id last recorded for parent `index`. */
  @inline def seen(index: Int): Int = slots(index)

  /** Remember `updateId` as the one last seen from parent `index`. */
  @inline def record(index: Int, updateId: Int): Unit = slots.update(index, updateId)
}

private[airstream] object UpdateIdSlots {

  /** One slot per parent, each holding 0 until it is recorded. */
  def sized(length: Int): UpdateIdSlots = {
    val slots = JsArray[Int]()
    var index = 0
    while (index < length) {
      slots.push(0)
      index += 1
    }
    new UpdateIdSlots(slots)
  }
}
