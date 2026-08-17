package com.raquo.airstream.platform

import scala.collection.mutable

/** Delayed callbacks on a platform with no event loop.
  *
  * This is the one abstraction where JavaScript and the non-JavaScript targets genuinely differ rather than merely being
  * spelled differently. JavaScript's event loop delivers a callback by itself. Native and JVM hosts instead decide when
  * to look. Rather than start a thread and take on its locking — Airstream's transaction model assumes a single thread
  * owning the graph — callbacks wait in a queue until the host says "run whatever is due".
  *
  * In a terminal application that caller already exists: the loop that blocks on the keyboard with a short timeout. Every
  * time the read comes back, due timers get their turn. Timing therefore becomes a property of a loop that is already
  * there, rather than of a thread nobody asked for.
  *
  * The clock is an argument to [[runDue]] rather than something read from inside. That makes every timing case testable
  * without a single sleep, which is what turns a flaky suite into a deterministic one.
  */
object Timers {

    /** A scheduled one-shot callback that has not fired yet. */
    opaque type TimerHandle = Long

    /** A scheduled repeating callback that has not been cancelled. */
    opaque type IntervalHandle = Long

    /** One waiting callback: when it is due, whether it repeats, and what to run. */
    private final case class Pending(handle: Long, dueAt: Double, repeatEvery: Option[Double], body: () => Unit)

    private var nextHandle: Long             = 0L
    private val pending: mutable.ListBuffer[Pending] = mutable.ListBuffer.empty

    /** How many callbacks are waiting. Exposed so a driver can tell whether idling is safe. */
    def pendingCount: Int = pending.size

    /** Run `body` once, no sooner than `delayMillis` after the clock value the next [[runDue]] reports.
      *
      * The deadline is relative to the caller's own clock rather than to a reading taken here, because a scheduler that
      * reads the clock itself cannot be tested without waiting for real time to pass.
      *
      * @return
      *   a handle that cancels it
      */
    def setTimeout(delayMillis: Double)(body: => Unit): TimerHandle =
        schedule(delayMillis, repeatEvery = None)(body)

    /** Cancel a scheduled one-shot callback.
      *
      * Accepts a handle that already fired or was already cancelled: a caller usually cannot know which, and making it
      * find out would push the bookkeeping back out to every call site.
      */
    def clearTimeout(handle: TimerHandle): Unit = cancel(handle)

    /** Run `body` repeatedly, no sooner than `intervalMillis` between eligible pumps.
      *
      * @return
      *   a handle that cancels every future repetition
      */
    def setInterval(intervalMillis: Double)(body: => Unit): IntervalHandle = {
        val period = math.max(0.0, intervalMillis)
        schedule(period, repeatEvery = Some(period))(body)
    }

    /** Cancel a repeating callback. Accepts a handle that already fired or was already cancelled. */
    def clearInterval(handle: IntervalHandle): Unit = cancel(handle)

    private def schedule(delayMillis: Double, repeatEvery: Option[Double])(body: => Unit): Long = {
        val handle = nextHandle
        nextHandle += 1
        pending += Pending(handle, currentTime + math.max(0.0, delayMillis), repeatEvery, () => body)
        handle
    }

    private def cancel(handle: Long): Unit = {
        pending.indexWhere(_.handle == handle) match {
            case -1    => ()
            case index => pending.remove(index)
        }
    }

    /** The current time in milliseconds as last supplied by the event-loop driver.
      *
      * Reading the pumped clock keeps deadline calculation and `now()` on the same time source. Production drivers pass
      * wall-clock milliseconds to [[runDue]]; deterministic tests pass a clock they control.
      *
      * @return
      *   the driver's latest time in milliseconds
      */
    def now(): Double = currentTime

    /** Forget every waiting callback. Exists for tests, which need to start from a known queue. */
    def clearAll(): Unit = {
        pending.clear()
        currentTime = 0.0
    }

    /** Run every callback whose deadline has been reached by `now`, earliest first.
      *
      * Callbacks scheduled from inside one of these do not run in the same pump. Letting them would make a single pump
      * able to loop forever on a timer that reschedules itself with a zero delay, which is exactly what a periodic stream
      * does. They run on the next pump instead, which keeps each pump bounded.
      *
      * @return
      *   how many callbacks ran, so a driver can tell whether it made progress
      */
    def runDue(now: Double): Int = {
        currentTime = now
        val due = pending.filter(_.dueAt <= now).sortBy(_.dueAt).toList
        var ran = 0
        due.foreach { entry =>
            val pendingIndex = pending.indexWhere(_.handle == entry.handle)
            if(pendingIndex >= 0) {
                pending.remove(pendingIndex)
                entry.repeatEvery.foreach(period => pending += entry.copy(dueAt = now + period))
                entry.body()
                ran += 1
            }
        }
        ran
    }

    /** The clock as last reported to [[runDue]], which is what a new deadline is measured from. */
    private var currentTime: Double = now()
}
