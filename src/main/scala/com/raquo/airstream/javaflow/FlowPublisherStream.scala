package com.raquo.airstream.javaflow

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.custom.CustomSource.{FireError, FireValue}
import com.raquo.airstream.platform.AtomicCell

import java.util.concurrent.Flow

/** An event stream over a `java.util.concurrent.Flow.Publisher`: each start subscribes once, each stop cancels.
  *
  * A publisher calls its subscriber from threads of its own choosing, and its serial-callback guarantee says nothing about
  * when the stream starts or stops. Every start therefore gets an endpoint of its own, the one owner of
  * the subscription that start receives, and the endpoint decides the handshake by atomic steps: a subscription that
  * arrives after the stream stopped is cancelled at once and never asked for anything, and a subscription is cancelled
  * at most once.
  */
object FlowPublisherStream {

  def apply[A](publisher: Flow.Publisher[A], emitOnce: Boolean = false): EventStream[A] = {
    // Touched only by the start and stop callbacks, which run inside the stream's runtime.
    var endpoint: PublisherEndpoint[A] = null

    EventStream.fromCustomSource[A](
      shouldStart = startIndex => if (emitOnce) startIndex == 1 else true,
      start = (fireEvent, fireError, _, _) => {
        val started = new PublisherEndpoint[A](fireEvent, fireError)
        endpoint = started
        publisher.subscribe(started)
      },
      stop = _ => {
        if (endpoint ne null) {
          endpoint.close()
          endpoint = null
        }
      }
    )
  }
}

/** The subscriber of one stream lifetime. Its state moves from waiting for `onSubscribe`, to holding the subscription, to
  * closed; each move is one atomic step, so `onSubscribe` on a publisher thread and `close` in the stream's runtime cannot
  * both believe they own the subscription.
  */
private[javaflow] final class PublisherEndpoint[A](fireEvent: FireValue[A], fireError: FireError) extends Flow.Subscriber[A] {

  private val state: AtomicCell[AnyRef] = new AtomicCell[AnyRef](PublisherEndpoint.Waiting)

  override def onSubscribe(subscription: Flow.Subscription): Unit = {
    if (state.compareAndSet(PublisherEndpoint.Waiting, subscription)) {
      // Stored before the request, because a publisher may emit synchronously from inside `request`.
      subscription.request(Long.MaxValue)
    } else {
      // The stream stopped before the handshake finished, or the publisher sent a second subscription: nobody owns it.
      subscription.cancel()
    }
  }

  override def onNext(value: A): Unit = fireEvent(value)

  override def onError(err: Throwable): Unit = fireError(err)

  override def onComplete(): Unit = ()

  /** End this lifetime: cancel the subscription when it already arrived, or make a later `onSubscribe` cancel it. */
  def close(): Unit = {
    state.getAndSet(PublisherEndpoint.Closed) match {
      case subscription: Flow.Subscription => subscription.cancel()
      case _ => ()
    }
  }
}

private[javaflow] object PublisherEndpoint {

  /** Before `onSubscribe`. */
  val Waiting: AnyRef = new Object

  /** After `close`. */
  val Closed: AnyRef = new Object
}
