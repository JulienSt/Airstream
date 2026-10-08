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

  /** A one-shot callback in the default runtime. Keep the original Long erasure for compiled host loops. */
  opaque type TimerHandle = Long

  /** A scheduled repeating callback that has not been cancelled. */
  opaque type IntervalHandle = Long

  /** Immutable queue entry. Its original binary name is retained; each RuntimeTimers owns its mutable queue. */
  private[platform] final case class Pending(handle: Long, dueAt: Double, repeatEvery: Option[Double], body: () => Unit)

  /** How many callbacks are waiting in the default runtime. */
  def pendingCount: Int = Runtime.default.timers.pendingCount

  /** Schedule `body` once in the default runtime; see `RuntimeTimers.setTimeout`. */
  def setTimeout(delayMillis: Double)(body: => Unit): TimerHandle =
    Runtime.default.timers.schedule(delayMillis, repeatEvery = None)(body)

  /** Cancel a one-shot callback of the default runtime. */
  def clearTimeout(handle: TimerHandle): Unit = Runtime.default.timers.cancel(handle)

  /** Schedule `body` repeatedly in the default runtime; see `RuntimeTimers.setInterval`. */
  def setInterval(intervalMillis: Double)(body: => Unit): IntervalHandle = {
    val period = math.max(0.0, intervalMillis)
    Runtime.default.timers.schedule(period, repeatEvery = Some(period))(body)
  }

  /** Cancel a repeating callback of the default runtime. */
  def clearInterval(handle: IntervalHandle): Unit = Runtime.default.timers.cancel(handle)

  /** The default runtime's clock, as last reported to [[runDue]]. */
  def now(): Double = Runtime.default.timers.now()

  /** Forget every waiting callback of the default runtime. Exists for tests. */
  def clearAll(): Unit = Runtime.default.timers.clearAll()

  /** Run every due callback of the default runtime; see `RuntimeTimers.runDue`. */
  def runDue(now: Double): Int = Runtime.default.timers.runDue(now)
}
