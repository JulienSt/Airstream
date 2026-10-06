package com.raquo.airstream.timing

import com.raquo.airstream.common.{InternalTryObserver, SingleParentStream}
import com.raquo.airstream.core.{EventStream, Transaction}
import org.scalajs.dom

import scala.scalajs.js
import scala.util.Try

/** This stream emits the last event emitted by `parent`, but only on the next
  * animation frame (as determined by the browser's `requestAnimationFrame`).
  *
  * Essentially, this stream throttles the parent's events to animation frames:
  * if the parent emits multiple events before the next animation frame, only
  * the last one is emitted, and it is emitted on the next animation frame.
  * Errors are propagated in the same manner (delayed).
  *
  * This is useful for expensive operations that you only want to run once per
  * rendered frame, e.g. reading layout metrics or updating the DOM.
  *
  * When stopped, this stream cancels its pending animation frame request and
  * "forgets" the event it was going to emit.
  *
  * See [[https://developer.mozilla.org/en-US/docs/Web/API/Window/requestAnimationFrame requestAnimationFrame @ MDN]]
  *
  * See also [[ThrottleStream]], [[DebounceStream]], [[DelayStream]]
  *
  * @param project `(parentEvent, timestampMs) => eventToEmit`
  *                Note: Exceptions thrown here are emitted as errors.
  */
class AnimationFrameStream[I, O](
  override protected val parent: EventStream[I],
  project: (I, Double) => O
) extends SingleParentStream[I, O] with InternalTryObserver[I] {

  /** Async stream, so reset rank */
  override protected val topoRank: Int = 1

  private var maybeRequestHandle: js.UndefOr[Int] = js.undefined

  /** Which frame request is the current one; a frame delivered for an older request is stale. */
  private var currentRequest: Int = 0

  override protected def onTry(nextValue: Try[I], transaction: Transaction): Unit = {
    maybeRequestHandle.foreach(dom.window.cancelAnimationFrame)
    currentRequest += 1
    val request = currentRequest
    // The frame callback runs from the event loop, outside every turn: the frame is delivered through the runtime, and
    // whether it is still the current request is decided there.
    maybeRequestHandle = dom.window.requestAnimationFrame(ts => runtime.deliver(onFrame(request, nextValue, ts)))
  }

  /** Apply a frame inside the runtime, unless a later request or a stop superseded it meanwhile. */
  private def onFrame(request: Int, nextValue: Try[I], ts: Double): Unit = {
    if (request == currentRequest) {
      maybeRequestHandle = js.undefined
      Transaction(runtime) { trx =>
        fireTry(nextValue.map(value => project(value, ts)), trx)
      }
    }
  }

  override protected def onStop(): Unit = {
    maybeRequestHandle.foreach(dom.window.cancelAnimationFrame)
    maybeRequestHandle = js.undefined
    currentRequest += 1
    super.onStop()
  }
}
