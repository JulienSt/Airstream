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
  * here schedules anything or owns a thread: Airstream's transaction model assumes one thread owning the graph, and a
  * signal firing from a pool would break that from the outside.
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
      lazyFuture.onComplete(onFutureCompleted)
    }
  }

  private def onFutureCompleted(completed: Try[A]): Unit = {
    // #Note Normally onWillStart must not create transactions or emit values, but it is fine here: the callback always
    //  arrives asynchronously, long after the onWillStart / onStart chain has finished.
    Transaction(fireTry(completed.map(Some(_)), _))
  }
}
