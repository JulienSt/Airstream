package com.raquo.airstream.javaflow

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.EventStream
import com.raquo.airstream.fixtures.TestableOwner

import java.util.concurrent.Flow
import scala.collection.mutable

/** The publisher handshake under a hostile but contract-valid publisher: `onSubscribe` may arrive after
  * the stream stopped, a `request` may emit synchronously, and the publisher may complete while the stream cancels. Each
  * subscription the stream receives has one owner, the stream lifetime that subscribed, and exactly one cleanup.
  */
class FlowPublisherHandshakeSpec extends UnitSpec {

  /** One subscription as the publisher sees it: how much was requested and how often it was cancelled. */
  final class Handle extends Flow.Subscription {
    var requested: Long = 0L
    var cancelled: Int = 0
    def request(n: Long): Unit = requested += n
    def cancel(): Unit = cancelled += 1
  }

  /** A publisher that keeps its subscribers and lets the spec decide when each handshake step happens. */
  final class HeldPublisher extends Flow.Publisher[Int] {
    val subscribers: mutable.Buffer[Flow.Subscriber[? >: Int]] = mutable.Buffer()
    def subscribe(subscriber: Flow.Subscriber[? >: Int]): Unit = subscribers += subscriber
  }

  it("cancels a subscription whose onSubscribe arrives after the stream stopped, without requesting from it") {
    val owner = new TestableOwner
    val publisher = new HeldPublisher
    val seen = mutable.Buffer[Int]()
    val subscription = EventStream.fromPublisher(publisher).foreach(seen += _)(owner)
    subscription.kill()
    val late = new Handle
    publisher.subscribers.head.onSubscribe(late)
    late.cancelled shouldBe 1
    late.requested shouldBe 0L
    seen.toList shouldBe Nil
  }

  it("keeps a stopped lifetime's late events out of the restarted stream: only the current subscription delivers") {
    val owner = new TestableOwner
    val publisher = new HeldPublisher
    val stream = EventStream.fromPublisher(publisher)
    val seen = mutable.Buffer[Int]()
    val first = stream.foreach(seen += _)(owner)
    publisher.subscribers.head.onSubscribe(new Handle)
    first.kill()
    stream.foreach(seen += _)(owner)
    publisher.subscribers(1).onSubscribe(new Handle)
    publisher.subscribers.head.onNext(1)
    publisher.subscribers(1).onNext(2)
    seen.toList shouldBe List(2)
  }

  it("leaves a subscription its publisher completed to the publisher: stopping afterwards cancels nothing") {
    val owner = new TestableOwner
    val publisher = new HeldPublisher
    val subscription = EventStream.fromPublisher(publisher).foreach(_ => ())(owner)
    val handle = new Handle
    publisher.subscribers.head.onSubscribe(handle)
    publisher.subscribers.head.onComplete()
    subscription.kill()
    handle.cancelled shouldBe 0
  }

  it("delivers what a request emits synchronously inside the start, and cancels that subscription exactly once on stop") {
    val owner = new TestableOwner
    val handle = new Handle
    val publisher = new Flow.Publisher[Int] {
      def subscribe(subscriber: Flow.Subscriber[? >: Int]): Unit = {
        subscriber.onSubscribe(new Flow.Subscription {
          def request(n: Long): Unit = {
            handle.request(n)
            subscriber.onNext(1)
            subscriber.onNext(2)
          }
          def cancel(): Unit = handle.cancel()
        })
      }
    }
    val seen = mutable.Buffer[Int]()
    val subscription = EventStream.fromPublisher(publisher).foreach(seen += _)(owner)
    seen.toList shouldBe List(1, 2)
    subscription.kill()
    handle.cancelled shouldBe 1
    handle.requested shouldBe Long.MaxValue
  }
}
