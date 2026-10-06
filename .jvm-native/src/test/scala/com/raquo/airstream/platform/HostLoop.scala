package com.raquo.airstream.platform

/** The host side of the default runtime's clock, for the shared [[TimersSpec]].
  *
  * Without an event loop the clock reads whatever the host last reported to `Timers.runDue`, and zero before the first
  * report. A spec that reads it must therefore make that report itself: otherwise it reads whatever time an earlier spec
  * left in the process-wide default runtime, and passes or fails depending on the order the specs run in.
  */
object HostLoop {

  /** Start the default runtime from an empty queue and report `millis` as the host's current time. */
  def reportTime(millis: Double): Unit = {
    Timers.clearAll()
    Timers.runDue(millis)
    ()
  }
}
