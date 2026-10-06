package com.raquo.airstream.platform

/** The update id a multi-parent signal last saw from each of its parents, one slot per parent, on Scala.js.
  *
  * Mutable because it is per-node state rewritten on every event the node receives, touched only under its runtime's
  * permit. A JavaScript array holds numbers unboxed already, and it stays the representation here so the
  * erased signature of the member that holds it is the one Airstream has always published (MiMa against 17.2.0). The array
  * never escapes: the length is fixed when the slots are made, and a slot is only read with `seen` and written with
  * `record`.
  */
private[airstream] opaque type UpdateIdSlots = JsArray[Int]

private[airstream] object UpdateIdSlots {

  /** One slot per parent, each holding 0 until it is recorded. */
  def sized(length: Int): UpdateIdSlots = {
    val slots = JsArray[Int]()
    var index = 0
    while (index < length) {
      slots.push(0)
      index += 1
    }
    slots
  }

  extension (slots: UpdateIdSlots) {

    /** The update id last recorded for parent `index`. */
    inline def seen(index: Int): Int = slots(index)

    /** Remember `updateId` as the one last seen from parent `index`. */
    inline def record(index: Int, updateId: Int): Unit = slots.update(index, updateId)
  }
}
