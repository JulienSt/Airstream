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

    /** Run `body` once, no sooner than `delayMillis` from now.
      *
      * @return
      *   a handle that cancels it
      */
    def setTimeout(delayMillis: Double)(body: => Unit): TimerHandle =
        js.timers.setTimeout(delayMillis)(body)

    /** Cancel a scheduled callback. Accepts a handle that already fired, because a caller cannot always know. */
    def clearTimeout(handle: TimerHandle): Unit =
        js.timers.clearTimeout(handle)
}
