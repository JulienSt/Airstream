package com.raquo.airstream.timing

import com.raquo.airstream.core.{Transaction, WritableStream}

import scala.scalajs.js
import scala.scalajs.js.|

/** This stream emits a value that the promise resolves with, even if the promise
  * was already resolved.
  *
  * This stream emits only once. If you want to remember the value, Use [[JsPromiseSignal]] instead.
  *
  * @param promise Note: guarded against failures
  */
class JsPromiseStream[A](
  promise: => js.Promise[A],
  emitOnce: Boolean
) extends WritableStream[A] {

  override protected val topoRank: Int = 1

  // #Note: It is not possible to synchronously check if a Javascript promise has been resolved or not,
  //  so we can't base our logic on whether the promise was resolved or not at a particular time.

  private var shouldSubscribe: Boolean = true

  private var isPending: Boolean = false

  private lazy val lazyPromise = promise

  override protected def onWillStart(): Unit = {
    if (shouldSubscribe && !isPending) {
      if (emitOnce) {
        shouldSubscribe = false
      }
      isPending = true
      lazyPromise.asInstanceOf[js.Dynamic].applyDynamic("then")( // #TODO[Scala] Can't use scala.js `then` method because https://github.com/scala/scala3/issues/20476
        // The callbacks run from the event loop, outside every turn: the settlement, pending flag included, is delivered
        // through the owning runtime.
        (
          (nextValue: A) => runtime.deliver(onResolved(nextValue))
        ): js.Function1[A, Unit],
        (
          (rawException: Any) => runtime.deliver(onRejected(rawException))
        ): js.Function1[Any, Unit]
      )
    }
  }

  /** Apply a resolution inside the runtime: clear the pending flag, then emit in a new transaction. */
  private def onResolved(nextValue: A): Unit = {
    isPending = false
    Transaction(runtime)(fireValue(nextValue, _))
  }

  /** Apply a rejection inside the runtime: clear the pending flag, then emit the error in a new transaction. */
  private def onRejected(rawException: Any): Unit = {
    isPending = false
    val nextError = rawException match {
      case th: Throwable => th
      case _ => js.JavaScriptException(rawException)
    }
    Transaction(runtime)(fireError(nextError, _))
  }
}
