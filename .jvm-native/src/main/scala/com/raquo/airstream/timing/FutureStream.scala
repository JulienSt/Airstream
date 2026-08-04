package com.raquo.airstream.timing

import com.raquo.airstream.core.{Transaction, WritableStream}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Emits the value a future completes with, even if it had already completed by the time anyone subscribed.
  *
  * The Scala.js build reaches a future by first turning it into a JavaScript promise, because on that platform a promise is
  * the native currency and the existing stream is written against one. Outside a JavaScript runtime there is no promise to
  * turn it into, so the future is observed directly — which is simpler, and closer to what the caller wrote.
  *
  * Whether the callback ever runs is the execution context's business, exactly as it is on the JVM. This stream does not
  * schedule anything and deliberately owns no thread: Airstream's transaction model assumes one thread owning the graph,
  * and a stream that fired from a pool would break that assumption from the outside.
  *
  * Emits once. Use a signal if the value needs remembering.
  *
  * @param future
  *   Note: guarded against failures
  */
class FutureStream[A](
  future: => Future[A],
  emitOnce: Boolean
)(implicit ec: ExecutionContext
) extends WritableStream[A] {

  override protected val topoRank: Int = 1

  private var shouldSubscribe: Boolean = true

  private var isPending: Boolean = false

  private lazy val lazyFuture = future

  override protected def onWillStart(): Unit = {
    if (shouldSubscribe && !isPending) {
      if (emitOnce) {
        shouldSubscribe = false
      }
      isPending = true
      lazyFuture.onComplete {
        case Success(nextValue) =>
          isPending = false
          Transaction(fireValue(nextValue, _))
        case Failure(nextError) =>
          isPending = false
          Transaction(fireError(nextError, _))
      }
    }
  }
}
