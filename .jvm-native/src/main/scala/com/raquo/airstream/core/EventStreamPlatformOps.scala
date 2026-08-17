package com.raquo.airstream.core

import com.raquo.airstream.timing.FutureStream

import scala.concurrent.{ExecutionContext, Future}

/** The stream constructors this platform provides in place of the JavaScript-shaped ones.
  *
  * `fromFuture` keeps its shared signature and changes only its route: the Scala.js build turns the future into a promise
  * first because a promise is that platform's native currency, and here the future is observed directly.
  *
  * The promise constructors themselves have no counterpart and are simply absent. Nothing is lost by that — a future is
  * what Scala code has anyway, and the promise overloads existed to accept what JavaScript hands out.
  */
trait EventStreamPlatformOps {

  /** Emit the value `future` completes with, even if it had already completed by the time anyone subscribed. */
  def fromFuture[A](future: => Future[A], emitOnce: Boolean = false)(implicit ec: ExecutionContext): EventStream[A] = {
    new FutureStream[A](future, emitOnce)
  }
}
