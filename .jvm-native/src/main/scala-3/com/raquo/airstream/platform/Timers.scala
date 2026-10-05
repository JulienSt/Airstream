package com.raquo.airstream.platform

import com.raquo.airstream.core.Runtime

/** The default runtime's delayed callbacks, on a platform with no event loop.
  *
  * This is the one abstraction where JavaScript and the non-JavaScript targets genuinely differ rather than merely being
  * spelled differently. JavaScript's event loop delivers a callback by itself. Native and JVM hosts instead decide when
  * to look: callbacks wait in a queue until the host says "run whatever is due" (see [[RuntimeTimers]]).
  *
  * The queue belongs to a runtime. Everything here addresses the default runtime, which is where an application that
  * never creates another runtime lives, so the host loop that pumps `Timers.runDue` keeps working unchanged. A further
  * runtime is pumped through its own `runtime.timers`.
  */
object Timers {

  /** A scheduled one-shot callback that has not fired yet. */
  type TimerHandle = RuntimeTimers.TimerHandle

  /** A scheduled repeating callback that has not been cancelled. */
  type IntervalHandle = RuntimeTimers.IntervalHandle

  /** How many callbacks are waiting in the default runtime. */
  def pendingCount: Int = Runtime.default.timers.pendingCount

  /** Schedule `body` once in the default runtime; see `RuntimeTimers.setTimeout`. */
  def setTimeout(delayMillis: Double)(body: => Unit): TimerHandle = Runtime.default.timers.setTimeout(delayMillis)(body)

  /** Cancel a one-shot callback of the default runtime. */
  def clearTimeout(handle: TimerHandle): Unit = Runtime.default.timers.clearTimeout(handle)

  /** Schedule `body` repeatedly in the default runtime; see `RuntimeTimers.setInterval`. */
  def setInterval(intervalMillis: Double)(body: => Unit): IntervalHandle =
    Runtime.default.timers.setInterval(intervalMillis)(body)

  /** Cancel a repeating callback of the default runtime. */
  def clearInterval(handle: IntervalHandle): Unit = Runtime.default.timers.clearInterval(handle)

  /** The default runtime's clock, as last reported to [[runDue]]. */
  def now(): Double = Runtime.default.timers.now()

  /** Forget every waiting callback of the default runtime. Exists for tests. */
  def clearAll(): Unit = Runtime.default.timers.clearAll()

  /** Run every due callback of the default runtime; see `RuntimeTimers.runDue`. */
  def runDue(now: Double): Int = Runtime.default.timers.runDue(now)
}
