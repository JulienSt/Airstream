package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.platform.Timers

import scala.collection.mutable

/** On a platform without an event loop the host pumps timers. Each runtime keeps its own queue, logical clock and
  * handles, so pumping one runtime never fires another runtime's callbacks, and a due callback runs
  * inside its runtime: readiness only says "now", the runtime entry is what admits the graph access.
  */
class RuntimeTimersSpec extends UnitSpec {

  it("keeps a confined runtime's timers in its own queue and runs a due callback inside that runtime") {
    val runtime = Runtime.confined()
    val seen = mutable.Buffer[Runtime]()
    runtime.run(runtime.timers.setTimeout(10)(seen += Runtime.current))
    Timers.runDue(100)
    seen.toList shouldBe Nil
    runtime.timers.pendingCount shouldBe 1
    runtime.timers.runDue(100) shouldBe 1
    seen.toList shouldBe List(runtime)
    runtime.isEntered shouldBe false
  }

  it("delays a stream of a confined runtime on that runtime's clock, not on the default runtime's") {
    val runtime = Runtime.confined()
    val owner = runtime.run(new ManualOwner)
    val bus = runtime.run(new EventBus[Int])
    val delayed = runtime.run(bus.events.delay(50))
    val seen = mutable.Buffer[Int]()
    delayed.foreach(seen += _)(owner)
    bus.emit(1)
    Timers.runDue(1000)
    seen.toList shouldBe Nil
    runtime.timers.runDue(1000)
    seen.toList shouldBe List(1)
    owner.killSubscriptions()
  }
}
