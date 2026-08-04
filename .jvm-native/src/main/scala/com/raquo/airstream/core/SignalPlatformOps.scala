package com.raquo.airstream.core

import com.raquo.airstream.timing.FutureSignal

import scala.concurrent.{ExecutionContext, Future}

/** The signal constructors this platform provides in place of the JavaScript-shaped ones.
  *
  * `fromFuture` keeps its shared signature and changes only its route: the Scala.js build turns the future into a promise
  * first, and here the future is observed directly.
  *
  * The promise constructors have no counterpart and are simply absent. They existed to accept what JavaScript hands out;
  * a future is what Scala code has anyway.
  */
trait SignalPlatformOps {

  /** Starts at `None` and updates once the future completes, even if it had already completed by then. */
  def fromFuture[A](future: => Future[A])(implicit ec: ExecutionContext): Signal[Option[A]] = {
    new FutureSignal(future)
  }

  /** Starts at `initial` and updates once the future completes, even if it had already completed by then. */
  def fromFuture[A](future: => Future[A], initial: => A)(implicit ec: ExecutionContext): Signal[A] = {
    new FutureSignal(future).map {
      case None => initial
      case Some(value) => value
    }
  }
}
