package com.raquo.airstream.core

import com.raquo.airstream.dynamicImport.DynamicImportStreamObjectOps
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.timing.{AnimationFrameStream, JsPromiseStream}

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

/** The stream constructors that only exist on Scala.js, plus the future one it implements through them.
  *
  * A promise is this platform's native currency, so a future reaches a stream by first becoming one. That is why
  * `fromFuture` lives here rather than in shared code: its signature is shared, its route is not.
  */
trait EventStreamPlatformOps extends DynamicImportStreamObjectOps { this: EventStream.type =>

  /** Emit the value `future` completes with, even if it had already completed by the time anyone subscribed. */
  def fromFuture[A](future: => Future[A], emitOnce: Boolean = false)(implicit ec: ExecutionContext): EventStream[A] = {
    fromJsPromise(future.toJSPromise(ec), emitOnce)
  }

  /** Emit the value `promise` resolves with, even if it had already resolved. */
  def fromJsPromise[A](promise: => js.Promise[A], emitOnce: Boolean = false): EventStream[A] = {
    new JsPromiseStream[A](promise, emitOnce)
  }

  /** `event` will be emitted in the browser's `requestAnimationFrame`.
    *
    * See docs for [[AnimationFrameStream]].
    *
    * See [[https://developer.mozilla.org/en-US/docs/Web/API/Window/requestAnimationFrame requestAnimationFrame @ MDN]]
    */
  def requestToAnimationFrame[A](event: => A, emitOnce: Boolean = false): EventStream[A] = {
    throttleToAnimationFrameWithTs(_ => event, emitOnce)
  }

  /** Result of `project(timestampMs)` will be emitted the browser's `requestAnimationFrame`.
    *
    * Emits tuples of `(event, timestampMs)`
    *
    * See docs for [[AnimationFrameStream]].
    *
    * See [[https://developer.mozilla.org/en-US/docs/Web/API/Window/requestAnimationFrame requestAnimationFrame @ MDN]]
    */
  def throttleToAnimationFrameWithTs[A](project: Double => A, emitOnce: Boolean = false): EventStream[A] = {
    new AnimationFrameStream[Unit, A](
      parent = EventStream.fromValue((), emitOnce),
      project = (_, ts) => project(ts)
    )
  }

}
