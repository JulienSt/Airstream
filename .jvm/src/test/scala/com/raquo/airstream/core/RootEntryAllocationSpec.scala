package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec

import java.lang.management.ManagementFactory

/** The entry itself allocates nothing on the JVM: installing and clearing the runtime context on every root
  * entry must reuse the thread's slot rather than remove it and create it again, which costs an allocation per entry on
  * the hottest path there is. Measured with HotSpot's per-thread allocation counter; JVM only, because Scala Native has no
  * such counter.
  */
class RootEntryAllocationSpec extends UnitSpec {

  private val threads = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  private def allocatedBy(entries: Int)(enter: () => Unit): Long = {
    val before = threads.getCurrentThreadAllocatedBytes
    var i = 0
    while (i < entries) {
      enter()
      i += 1
    }
    threads.getCurrentThreadAllocatedBytes - before
  }

  it("allocates nothing per root entry once warm") {
    val runtime = Runtime.confined()
    val enter: () => Unit = () => runtime.run(())
    allocatedBy(200000)(enter) // warm-up: let the JIT compile the entry and the slot settle
    val entries = 200000
    val bytes = allocatedBy(entries)(enter)
    withClue(s"$bytes bytes for $entries entries: ") {
      (bytes.toDouble / entries < 1.0) shouldBe true
    }
  }
}
