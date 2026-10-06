package com.raquo.airstream

import java.lang.management.ManagementFactory

/** What a piece of code allocates by construction, as opposed to what is left after the JIT's escape analysis.
  *
  * The calls run cold: a short warm-up resolves classes and lambda bootstraps, then a few hundred measured calls stay below
  * the threshold at which HotSpot compiles the measured body with C2, the only tier that scalar-replaces allocations. The
  * interpreter and C1 allocate every object the code asks for, so a result of zero bytes means the code asks for none, which
  * is the guarantee that survives a megamorphic call site in a real program. Measured with HotSpot's per-thread allocation
  * counter; JVM only.
  */
object AllocationMeter {

  private val threads = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  /** Bytes the calling thread allocates per call of `body`, over `calls` cold calls after `warmup` unmeasured ones. */
  def bytesPerCall(calls: Int = 500, warmup: Int = 50)(body: () => Unit): Double = {
    var index = 0
    while (index < warmup) {
      body()
      index += 1
    }
    val before = threads.getCurrentThreadAllocatedBytes
    index = 0
    while (index < calls) {
      body()
      index += 1
    }
    (threads.getCurrentThreadAllocatedBytes - before).toDouble / calls
  }
}
