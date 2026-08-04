package com.raquo.airstream.platform

import scala.collection.mutable

/** Delayed callbacks on a platform with no event loop.
  *
  * This is the one abstraction where the two platforms genuinely differ rather than merely being spelled differently. On
  * JavaScript the event loop delivers a callback by itself. A Scala Native program has no loop at all: something has to
  * decide when to look. Rather than start a thread and take on its locking — Airstream's whole transaction model assumes a
  * single thread owning the graph — the callbacks wait in a queue until a caller says "run whatever is due".
  *
  * In a terminal application that caller already exists: the loop that blocks on the keyboard with a short timeout. Every
  * time the read comes back, due timers get their turn. Timing therefore becomes a property of a loop that is already
  * there, rather than of a thread nobody asked for.
  *
  * The clock is an argument to [[runDue]] rather than something read from inside. That makes every timing case testable
  * without a single sleep, which is what turns a flaky suite into a deterministic one.
  */
object Timers {

    /** A scheduled callback that has not fired yet. */
    opaque type TimerHandle = Long

    /** One waiting callback: when it is due, and what to run. */
    private final case class Pending(handle: Long, dueAt: Double, body: () => Unit)

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
    def setTimeout(delayMillis: Double)(body: => Unit): TimerHandle = {
        val handle = nextHandle
        nextHandle += 1
        pending += Pending(handle, currentTime + math.max(0.0, delayMillis), () => body)
        handle
    }

    /** Cancel a scheduled callback.
      *
      * Accepts a handle that already fired or was already cancelled: a caller usually cannot know which, and making it
      * find out would push the bookkeeping back out to every call site.
      */
    def clearTimeout(handle: TimerHandle): Unit = {
        pending.indexWhere(_.handle == handle) match {
            case -1    => ()
            case index => pending.remove(index)
        }
    }

    /** The current time in milliseconds.
      *
      * Reads the wall clock rather than the pumped one, because a caller asking what time it is wants the real answer.
      * That makes it the driver's job to pass wall-clock milliseconds to [[runDue]], which is what keeps a deadline and a
      * reading comparable — and is why the two live in the same object instead of being read wherever they are needed.
      *
      * @return
      *   milliseconds since the epoch
      */
    def now(): Double = System.currentTimeMillis().toDouble

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
        due.foreach(entry => clearTimeout(entry.handle))
        due.foreach(_.body())
        due.size
    }

    /** The clock as last reported to [[runDue]], which is what a new deadline is measured from. */
    private var currentTime: Double = 0.0
}
