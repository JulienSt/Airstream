package com.raquo.airstream.platform

import scala.scalajs.js

/** Delayed callbacks on Scala.js, which is to say the platform's own.
  *
  * A straight forwarding to `js.timers`. The event loop already owns the scheduling, the ordering and the firing, and
  * nothing here should try to own any of it a second time.
  */
object Timers {

  /** A scheduled callback that has not fired yet. */
  type TimerHandle = js.timers.SetTimeoutHandle

  /** A scheduled repeating callback. */
  type IntervalHandle = js.timers.SetIntervalHandle

  /** Run `body` once, no sooner than `delayMillis` from now.
    *
    * @return
    *   a handle that cancels it
    */
  @inline def setTimeout(delayMillis: Double)(body: => Unit): TimerHandle =
    js.timers.setTimeout(delayMillis)(body)

  /** Cancel a scheduled callback. Accepts a handle that already fired, because a caller cannot always know. */
  @inline def clearTimeout(handle: TimerHandle): Unit =
    js.timers.clearTimeout(handle)

  /** Run `body` repeatedly, no sooner than `intervalMillis` between callbacks.
    *
    * @return
    *   a handle that cancels every future repetition
    */
  @inline def setInterval(intervalMillis: Double)(body: => Unit): IntervalHandle =
    js.timers.setInterval(intervalMillis)(body)

  /** Cancel a repeating callback. Accepts a handle that was already cancelled. */
  @inline def clearInterval(handle: IntervalHandle): Unit =
    js.timers.clearInterval(handle)

  /** The current time in milliseconds.
    *
    * Lives beside the scheduler rather than being read wherever it is needed, so a deadline and a reading always come
    * from the same clock. Throttling subtracts one reading from another and schedules against the difference; taking the
    * two from different sources is how a throttle fires at the wrong moment on one platform only.
    *
    * @return
    *   milliseconds since the epoch
    */
  @inline def now(): Double = js.Date.now()
}
