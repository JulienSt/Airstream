package com.raquo.airstream.platform

import com.raquo.airstream.core.AirstreamError.CrossRuntimeEdge
import com.raquo.airstream.core.Runtime

import scala.collection.mutable
import scala.scalajs.js

/** Delayed callbacks of one runtime on Scala.js: the event loop's own timers, with each callback entering its runtime.
  *
  * The event loop already owns the scheduling, the ordering and the firing, and nothing here tries to own any of it a
  * second time. What a runtime adds is the entry: a due callback is delivered through `Runtime.deliver`, so the
  * transactions it creates belong to the runtime that scheduled it rather than to whatever the page happens to be inside,
  * and a scheduled runtime receives it as a turn of its own instead of being entered from the event loop.
  */
final class RuntimeTimers(private val runtime: Runtime) {

  /** Run `body` once, no sooner than `delayMillis` from now, inside this runtime.
    *
    * @return
    *   a handle that cancels it
    */
  def setTimeout(delayMillis: Double)(body: => Unit): RuntimeTimers.TimerHandle = {
    var handle: RuntimeTimers.TimerHandle = null
    handle = new RuntimeTimers.TimerHandle(this, js.timers.setTimeout(delayMillis)(runtime.deliver(fire(handle, body))))
    liveTimeouts += handle
    handle
  }

  /** The timeouts that have not fired or been cleared, so the runtime's close can cancel them. */
  private val liveTimeouts: mutable.HashSet[RuntimeTimers.TimerHandle] = mutable.HashSet.empty

  /** The intervals that have not been cleared, so the runtime's close can cancel them. */
  private val liveIntervals: mutable.HashSet[RuntimeTimers.IntervalHandle] = mutable.HashSet.empty

  /** Inside the runtime: a timeout that fires is no longer live, then its body runs. */
  private def fire(handle: RuntimeTimers.TimerHandle, body: => Unit): Unit = {
    liveTimeouts -= handle
    body
  }

  /** Cancel a scheduled callback. Accepts a handle that already fired, because a caller cannot always know. */
  def clearTimeout(handle: RuntimeTimers.TimerHandle): Unit = {
    requireOwn(handle.owner)
    liveTimeouts -= handle
    js.timers.clearTimeout(handle.raw)
  }

  /** Run `body` repeatedly, no sooner than `intervalMillis` between callbacks, each inside this runtime.
    *
    * @return
    *   a handle that cancels every future repetition
    */
  def setInterval(intervalMillis: Double)(body: => Unit): RuntimeTimers.IntervalHandle = {
    val handle = new RuntimeTimers.IntervalHandle(this, js.timers.setInterval(intervalMillis)(runtime.deliver(body)))
    liveIntervals += handle
    handle
  }

  /** Cancel a repeating callback. Accepts a handle that was already cancelled. */
  def clearInterval(handle: RuntimeTimers.IntervalHandle): Unit = {
    requireOwn(handle.owner)
    liveIntervals -= handle
    js.timers.clearInterval(handle.raw)
  }

  /** Cancel every timeout and interval still live; the runtime's close calls this under the permit. */
  def cancelAll(): Unit = {
    liveTimeouts.foreach(handle => js.timers.clearTimeout(handle.raw))
    liveIntervals.foreach(handle => js.timers.clearInterval(handle.raw))
    liveTimeouts.clear()
    liveIntervals.clear()
  }

  /** Reject a handle of another runtime's timers: the event loop numbers every timer of the page in one sequence,
    * so cancelling through the wrong runtime would cancel a callback that runtime never scheduled.
    *
    * @throws CrossRuntimeEdge when the handle belongs to another runtime
    */
  private def requireOwn(owner: RuntimeTimers): Unit = {
    if (owner ne this) {
      throw CrossRuntimeEdge(Seq(runtime, owner.runtime))
    }
  }

  /** The current time in milliseconds, from the same clock the scheduler uses. */
  def now(): Double = js.Date.now()
}

object RuntimeTimers {

  /** A scheduled callback that has not fired yet: the runtime timers it belongs to and the event loop's handle. */
  final class TimerHandle private[RuntimeTimers] (
    private[RuntimeTimers] val owner: RuntimeTimers,
    private[RuntimeTimers] val raw: js.timers.SetTimeoutHandle
  )

  /** A scheduled repeating callback: the runtime timers it belongs to and the event loop's handle. */
  final class IntervalHandle private[RuntimeTimers] (
    private[RuntimeTimers] val owner: RuntimeTimers,
    private[RuntimeTimers] val raw: js.timers.SetIntervalHandle
  )
}
