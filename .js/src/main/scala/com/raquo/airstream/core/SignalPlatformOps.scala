package com.raquo.airstream.core

import com.raquo.airstream.dynamicImport.DynamicImportSignalObjectOps
import com.raquo.airstream.timing.JsPromiseSignal

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

/** The signal constructors that only exist on Scala.js, plus the future ones they implement.
  *
  * A promise is this platform's native currency, so a future reaches a signal by first becoming one. The `fromFuture`
  * signature is shared; only the route is not, which is why it lives here.
  */
trait SignalPlatformOps extends DynamicImportSignalObjectOps { this: Signal.type =>

  /** Starts at `None` and updates once the future completes, even if it had already completed by then. */
  def fromFuture[A](future: => Future[A])(implicit ec: ExecutionContext): Signal[Option[A]] = {
    fromJsPromise(future.toJSPromise(ec))
  }

  /** Starts at `initial` and updates once the future completes, even if it had already completed by then. */
  def fromFuture[A](future: => Future[A], initial: => A)(implicit ec: ExecutionContext): Signal[A] = {
    fromJsPromise(future.toJSPromise(ec), initial)
  }

  /** Starts at `None` and updates once the promise resolves, even if it had already resolved. */
  def fromJsPromise[A](promise: => js.Promise[A]): Signal[Option[A]] = {
    new JsPromiseSignal(promise)
  }

  /** Starts at `initial` and updates once the promise resolves, even if it had already resolved. */
  def fromJsPromise[A](promise: => js.Promise[A], initial: => A): Signal[A] = {
    new JsPromiseSignal(promise).map {
      case None => initial
      case Some(value) => value
    }
  }
}
