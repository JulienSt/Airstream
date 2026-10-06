package com.raquo.airstream.timing

import com.raquo.airstream.core.{Transaction, WritableSignal}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

/** Starts at `None` and updates once the future completes, even if it had already completed by then.
  *
  * The counterpart of the Scala.js promise signal, and it starts at `None` for the same reason that one does: a signal
  * must have a value the moment it is asked, and a future that has not completed has nothing to offer yet.
  *
  * A signal rather than a stream because the value has to survive being unobserved and observed again. A stream that
  * fired once and stopped would leave a later subscriber with nothing, which is precisely the difference the two types
  * exist to express.
  *
  * Whether the completion callback ever runs is the execution context's business, exactly as it is on the JVM. Nothing
  * here owns a thread or touches the signal on the callback's thread: the completion is handed to the signal's runtime,
  * which applies it under its permit (`Runtime.deliver`).
  */
class FutureSignal[A](
  future: => Future[A]
)(implicit ec: ExecutionContext
) extends WritableSignal[Option[A]] {

  override protected val topoRank: Int = 1

  private var futureSubscribed: Boolean = false

  private lazy val lazyFuture = future

  setCurrentValue(Success(None))

  // There is no way to pull a value out of a future on demand either, so this matches the promise signal exactly.
  override protected def currentValueFromParent(): Try[Option[A]] = tryNow() // noop

  override protected def onWillStart(): Unit = {
    if (!futureSubscribed) {
      futureSubscribed = true
      lazyFuture.onComplete(completed => runtime.deliver(onFutureCompleted(completed)))
    }
  }

  /** Apply a completion inside the runtime, in a new transaction. */
  private def onFutureCompleted(completed: Try[A]): Unit = {
    // #Note Normally onWillStart must not create transactions or emit values, but it is fine here: the completion is
    //  delivered through the runtime, inline only when the callback already runs inside its turn, and a transaction
    //  created during a shared start waits for the start to finish.
    Transaction(runtime)(fireTry(completed.map(Some(_)), _))
  }
}
