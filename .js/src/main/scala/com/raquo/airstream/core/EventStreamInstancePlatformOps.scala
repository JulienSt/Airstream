package com.raquo.airstream.core

import com.raquo.airstream.dynamicImport.DynamicImportStreamOps
import com.raquo.airstream.timing.AnimationFrameStream

/** EventStream instance members supplied by the JavaScript platform. */
trait EventStreamInstancePlatformOps[+A] extends DynamicImportStreamOps[A] { this: EventStream[A] =>

  /** Incoming events will be throttled using the browser's `requestAnimationFrame`.
    *
    * See docs for [[AnimationFrameStream]].
    *
    * See [[https://developer.mozilla.org/en-US/docs/Web/API/Window/requestAnimationFrame requestAnimationFrame @ MDN]]
    */
  def throttleWithAnimationFrame: EventStream[A] = {
    new AnimationFrameStream[A, A](parent = this, project = (ev, _) => ev)
  }

  /** Incoming events will be throttled using the browser's `requestAnimationFrame`.
    *
    * Emits tuples of `(event, timestampMs)`
    *
    * See docs for [[AnimationFrameStream]].
    *
    * See [[https://developer.mozilla.org/en-US/docs/Web/API/Window/requestAnimationFrame requestAnimationFrame @ MDN]]
    */
  def throttleWithAnimationFrameWithTs: EventStream[(A, Double)] = {
    new AnimationFrameStream[A, (A, Double)](parent = this, project = (ev, ts) => (ev, ts))
  }

}
