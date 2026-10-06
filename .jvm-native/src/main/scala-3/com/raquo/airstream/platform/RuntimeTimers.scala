package com.raquo.airstream.platform

import com.raquo.airstream.core.{Receipt, Runtime}
import com.raquo.airstream.core.AirstreamError.CrossRuntimeEdge

import scala.collection.mutable

/** Delayed callbacks of one runtime on a platform with no event loop.
  *
  * JavaScript's event loop delivers a callback by itself. Native and JVM hosts instead decide when to look: callbacks
  * wait in this queue until the host pumps it with [[runDue]], typically from the loop that is already blocking on the
  * keyboard with a short timeout. Timing therefore becomes a property of a loop that is already there, rather than of a
  * thread nobody asked for, and the clock is an argument so every timing case is testable without a single sleep.
  *
  * The queue, the logical clock and the handle counter belong to the runtime that owns this instance: pumping
  * one runtime never fires another runtime's callbacks, and a due callback runs inside its runtime (`runtime.run`), so
  * readiness only says "now" and the runtime entry is what admits the graph access.
  */
final class RuntimeTimers(private val runtime: Runtime) {

  import RuntimeTimers.{IntervalHandle, TimerHandle}

  /** One waiting callback: when it is due, whether it repeats, and what to run. */
  private final case class Pending(handle: Long, dueAt: Double, repeatEvery: Option[Double], body: () => Unit)

  private var nextHandle: Long = 0L

  private val pending: mutable.ListBuffer[Pending] = mutable.ListBuffer.empty

  /** The clock as last reported to [[runDue]], which is what a new deadline is measured from. */
  private var currentTime: Double = 0.0

  /** How many callbacks are waiting. Exposed so a driver can tell whether idling is safe. */
  def pendingCount: Int = runtime.run(pending.size)

  /** Run `body` once, no sooner than `delayMillis` after the clock value the next [[runDue]] reports.
    *
    * The deadline is relative to the caller's own clock rather than to a reading taken here, because a scheduler that
    * reads the clock itself cannot be tested without waiting for real time to pass.
    *
    * @return
    *   a handle that cancels it
    */
  def setTimeout(delayMillis: Double)(body: => Unit): TimerHandle =
    new TimerHandle(this, schedule(delayMillis, repeatEvery = None)(body))

  /** Cancel a scheduled one-shot callback.
    *
    * Accepts a handle that already fired or was already cancelled: a caller usually cannot know which, and making it
    * find out would push the bookkeeping back out to every call site.
    */
  def clearTimeout(handle: TimerHandle): Unit = cancel(idOf(handle.owner, handle.id))

  /** Run `body` repeatedly, no sooner than `intervalMillis` between eligible pumps.
    *
    * @return
    *   a handle that cancels every future repetition
    */
  def setInterval(intervalMillis: Double)(body: => Unit): IntervalHandle = {
    val period = math.max(0.0, intervalMillis)
    new IntervalHandle(this, schedule(period, repeatEvery = Some(period))(body))
  }

  /** Cancel a repeating callback. Accepts a handle that already fired or was already cancelled. */
  def clearInterval(handle: IntervalHandle): Unit = cancel(idOf(handle.owner, handle.id))

  /** The number a handle has in this runtime's queue; a handle of another runtime's timers is rejected, because
    * the same number there names a different callback.
    *
    * @throws CrossRuntimeEdge when the handle belongs to another runtime
    */
  private def idOf(owner: RuntimeTimers, id: Long): Long = {
    if (owner ne this) {
      throw CrossRuntimeEdge(Seq(runtime, owner.runtime))
    }
    id
  }

  // Scheduling, cancelling and reading the queue enter the runtime: the queue, the clock and the handle counter are
  // runtime state, so a thread the runtime does not admit is rejected before it touches them, and a
  // call from inside a turn is a free reentrant entry.

  private def schedule(delayMillis: Double, repeatEvery: Option[Double])(body: => Unit): Long = runtime.run {
    val handle = nextHandle
    nextHandle += 1
    pending += Pending(handle, currentTime + math.max(0.0, delayMillis), repeatEvery, () => body)
    handle
  }

  private def cancel(handle: Long): Unit = runtime.run {
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

  /** Cancel every waiting callback; the runtime's close calls this under the permit. */
  def cancelAll(): Unit = runtime.run {
    pending.clear()
  }

  /** Forget every waiting callback and reset the clock. Exists for tests, which need to start from a known queue. */
  def clearAll(): Unit = runtime.run {
    pending.clear()
    currentTime = 0.0
  }

  /** Run every callback whose deadline has been reached by `now`, earliest first, each inside this runtime.
    *
    * The pump enters the runtime before it touches the queue, so a thread the runtime does not admit is rejected with the
    * queue intact; a host outside a scheduled runtime reports readiness with [[submitDue]] instead.
    *
    * Callbacks scheduled from inside one of these do not run in the same pump. Letting them would make a single pump
    * able to loop forever on a timer that reschedules itself with a zero delay, which is exactly what a periodic stream
    * does. They run on the next pump instead, which keeps each pump bounded.
    *
    * @return
    *   how many callbacks ran, so a driver can tell whether it made progress
    */
  def runDue(now: Double): Int = runtime.run(runDueInside(now))

  /** Report readiness from any thread: run the callbacks due by `now` in a turn of this runtime.
    *
    * @return the receipt of the turn that pumps the queue
    */
  def submitDue(now: Double): Receipt = runtime.submit {
    runDueInside(now)
    ()
  }

  /** The pump itself, under the permit: the queue and the clock are runtime state. */
  private def runDueInside(now: Double): Int = {
    currentTime = now
    val due = pending.filter(_.dueAt <= now).sortBy(_.dueAt).toList
    var ran = 0
    due.foreach { entry =>
      val pendingIndex = pending.indexWhere(_.handle == entry.handle)
      if (pendingIndex >= 0) {
        pending.remove(pendingIndex)
        entry.repeatEvery.foreach(period => pending += entry.copy(dueAt = now + period))
        runtime.run(entry.body())
        ran += 1
      }
    }
    ran
  }
}

object RuntimeTimers {

  /** A scheduled one-shot callback that has not fired yet: the timers it was filed with and its number there. */
  final class TimerHandle private[RuntimeTimers] (
    private[RuntimeTimers] val owner: RuntimeTimers,
    private[RuntimeTimers] val id: Long
  )

  /** A scheduled repeating callback that has not been cancelled: the timers it was filed with and its number there. */
  final class IntervalHandle private[RuntimeTimers] (
    private[RuntimeTimers] val owner: RuntimeTimers,
    private[RuntimeTimers] val id: Long
  )
}
