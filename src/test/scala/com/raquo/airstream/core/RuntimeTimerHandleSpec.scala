package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.CrossRuntimeEdge

/** Timer handles identify their runtime: two runtimes may hand out equal handle numbers, so a
  * handle used with another runtime's timers is rejected instead of cancelling whatever that runtime filed under the same
  * number.
  */
class RuntimeTimerHandleSpec extends UnitSpec {

  it("rejects cancelling another runtime's timeout and interval handles") {
    val a = Runtime.confined()
    val b = Runtime.confined()
    val fromA = a.run(a.timers.setTimeout(10)(()))
    val intervalFromA = a.run(a.timers.setInterval(10)(()))
    b.run(b.timers.setTimeout(10)(()))
    b.run(b.timers.setInterval(10)(()))
    assertThrows[CrossRuntimeEdge](b.run(b.timers.clearTimeout(fromA)))
    assertThrows[CrossRuntimeEdge](b.run(b.timers.clearInterval(intervalFromA)))
    a.run(a.timers.clearTimeout(fromA))
    a.run(a.timers.clearInterval(intervalFromA))
  }
}
