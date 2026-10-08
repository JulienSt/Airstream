package com.raquo.airstream.platform

/** Parent update IDs stay in the original protected JsArray surface. JavaScript numbers already need no boxing. */
private[airstream] object UpdateIdSlots {

  @inline def sized(length: Int): JsArray[Int] = {
    val slots = JsArray[Int]()
    var index = 0
    while (index < length) {
      slots.push(0)
      index += 1
    }
    slots
  }

  @inline def seen(slots: JsArray[Int], index: Int): Int = slots(index)

  @inline def record(slots: JsArray[Int], index: Int, updateId: Int): Unit = slots.update(index, updateId)
}
