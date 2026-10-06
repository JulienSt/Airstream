package com.raquo.airstream.timing

import com.raquo.airstream.core.{Transaction, WritableStream}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** Emits the value a future completes with, even if it had already completed by the time anyone subscribed.
  *
  * The Scala.js build reaches a future by first turning it into a JavaScript promise, because on that platform a promise is
  * the native currency and the existing stream is written against one. Outside a JavaScript runtime there is no promise to
  * turn it into, so the future is observed directly — which is simpler, and closer to what the caller wrote.
  *
  * Whether the callback ever runs is the execution context's business, exactly as it is on the JVM. This stream owns no
  * thread and touches nothing on the callback's thread: the completion is handed to the stream's runtime, which applies
  * it under its permit (`Runtime.deliver`): at once when the callback runs inside the current turn or on a thread that may
  * enter a direct runtime, as a later turn of a scheduled runtime otherwise.
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
      // The callback runs wherever the execution context runs it; everything it does to this stream, the pending flag
      // included, is delivered through the owning runtime.
      lazyFuture.onComplete(completed => runtime.deliver(onFutureCompleted(completed)))
    }
  }

  /** Apply a completion inside the runtime: clear the pending flag, then emit in a new transaction. */
  private def onFutureCompleted(completed: Try[A]): Unit = {
    isPending = false
    completed match {
      case Success(nextValue) => Transaction(runtime)(fireValue(nextValue, _))
      case Failure(nextError) => Transaction(runtime)(fireError(nextError, _))
    }
  }
}
