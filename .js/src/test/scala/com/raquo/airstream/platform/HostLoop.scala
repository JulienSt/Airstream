package com.raquo.airstream.platform

/** The host side of the clock, for the shared [[TimersSpec]].
  *
  * On JavaScript the event loop is the host and the clock is the wall clock, which no test can set; it already reads a
  * time past the epoch. Reporting a time is therefore nothing to do here.
  */
object HostLoop {

  /** Nothing to report: the wall clock is already running. */
  def reportTime(millis: Double): Unit = ()
}
