package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.DeliveryRejected
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.platform.AtomicFlag
import com.raquo.airstream.state.Var

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

/** An explicit connection between two runtimes: values a source observable emits in one runtime are
  * carried, each as an immutable message with a bridge sequence number, into new transactions of a target runtime.
  *
  * A bridge is the supported way to connect graphs of different runtimes; a synchronous edge between them is rejected
  * (`CrossRuntimeEdge`). It transfers values and errors only, never a transaction, a permit or a subscription, and it
  * cannot make a mutable value safe: what crosses must be immutable, copied, or owned by a documented protocol.
  *
  *   - **Order.** Messages of one bridge are applied in the order the source accepted them: the sequence number decides,
  *     and the target buffers a message that arrives early. Two bridges, or a bridge and other submissions, have no order
  *     between them beyond the target's admission order.
  *   - **New transactions.** Each message is applied at the root of a target turn, so it starts a new target transaction;
  *     the source's transaction ends at the bridge.
  *   - **Where it runs.** A bridge is created inside a turn of the source's runtime, which attaches the source endpoint in
  *     that turn. The target must admit asynchronous submissions, a scheduled or a dispatched runtime, because the source
  *     hands messages over from its own thread.
  *   - **Closing.** `closeTarget()` acknowledges once no new target delivery can start; `close()` additionally detaches
  *     the source endpoint and releases both endpoints. Neither waits on one runtime from inside the other.
  */
sealed abstract class Bridge private[core] (private[core] val link: BridgeLink[?]) {

  /** Close the target endpoint: its receipt completes in a target turn after which no message is applied any more. The
    * source endpoint stays attached until `close`, so messages may still be sent and are dropped at the target.
    */
  def closeTarget(): Receipt = link.closeTarget()

  /** Close the whole bridge: the source endpoint is detached in a source turn, then the target endpoint is closed in a
    * target turn after every message the source sent before; the receipt completes when both are done.
    */
  def close(): Receipt = link.close()
}

/** A bridge for events: each event or error of the source stream is emitted on a target bus. */
final class EventBridge private[core] (link: BridgeLink[?]) extends Bridge(link)

object EventBridge {

  /** Bridge `source` into `target`; call inside a turn of `source`'s runtime. `target` belongs to a scheduled or dispatched
    * runtime, and each source event is emitted on it in a new transaction of that runtime.
    *
    * @throws IllegalArgumentException when the target runtime does not admit asynchronous submissions
    */
  def apply[A](source: EventStream[A], target: EventBus[A]): EventBridge = {
    new EventBridge(BridgeLink.attach[A](source, target.events.runtime, target.emitTry))
  }
}

/** A bridge for a signal: the target Var follows the source signal. */
final class SignalBridge private[core] (link: BridgeLink[?]) extends Bridge(link)

object SignalBridge {

  /** Bridge `source` into `target`; call inside a turn of `source`'s runtime. `target` belongs to a scheduled or dispatched
    * runtime and keeps its own initial value until the snapshot arrives.
    *
    * The snapshot is the source's own initial notification to the bridge's observer, taken in the turn that creates the
    * bridge: it is message 0 and represents every source change up to that cut, including what the source's startup
    * emitted synchronously; every later notification follows it exactly once, in order.
    *
    * @throws IllegalArgumentException when the target runtime does not admit asynchronous submissions
    */
  def apply[A](source: Signal[A], target: Var[A]): SignalBridge = {
    new SignalBridge(BridgeLink.attach[A](source, target.runtime, target.setTry))
  }
}

/** Both endpoints of one bridge. The source endpoint's fields belong to the source runtime and the target endpoint's to
  * the target runtime; each is touched only in that runtime's turns. The two flags are read across runtimes, by `close`.
  */
private[core] final class BridgeLink[A] private (
  sourceRuntime: Runtime,
  targetRuntime: Runtime,
  write: Try[A] => Unit
) {

  // Source endpoint, under the source runtime's permit.

  private val owner: ManualOwner = new ManualOwner

  private var nextSequence: Long = 0L

  private val sourceDetached: AtomicFlag = new AtomicFlag

  // Target endpoint, under the target runtime's permit.

  private var expected: Long = 0L

  private val early: mutable.HashMap[Long, Try[A]] = mutable.HashMap.empty

  private val targetClosed: AtomicFlag = new AtomicFlag

  /** In the source turn: number the notification and hand it to the target runtime. */
  private def send(value: Try[A]): Unit = {
    if (!sourceDetached.get) {
      val sequence = nextSequence
      nextSequence += 1
      val receipt = targetRuntime.submit(receive(sequence, value))
      receipt.accepted.value.flatMap(_.failed.toOption).foreach(refused)
    }
  }

  /** The target refused a message: the order would have a gap, so the bridge stops sending. A target that is closing or
    * closed refused on purpose; any other refusal (a full mailbox, a failed runtime) is reported to the source runtime.
    */
  private def refused(rejection: Throwable): Unit = {
    detachSource()
    if (targetRuntime.state eq RuntimeState.Open) {
      sourceRuntime.sendUnhandledError(DeliveryRejected(targetRuntime, rejection))
    }
  }

  /** In a target turn: apply the message when it is the next one, buffer it when it arrived early. */
  private[core] def receive(sequence: Long, value: Try[A]): Unit = {
    if (!targetClosed.get) {
      early.update(sequence, value)
      applyInOrder()
    }
  }

  private def applyInOrder(): Unit = {
    var next = early.remove(expected)
    while (next.isDefined) {
      val sequence = expected
      expected += 1
      targetRuntime.traceBridgeMessageDelivered(sequence)
      try {
        write(next.get)
      } catch {
        case NonFatal(err) => targetRuntime.sendUnhandledError(err)
      }
      next = early.remove(expected)
    }
  }

  /** In a source turn: stop observing the source. Idempotent. */
  private[core] def detachSource(): Unit = {
    if (sourceDetached.compareAndSet(false, true)) {
      owner.killSubscriptions()
    }
  }

  /** In a target turn: apply nothing any more and drop what is buffered. Idempotent. */
  private[core] def closeTargetEndpoint(): Unit = {
    targetClosed.set(true)
    early.clear()
  }

  def closeTarget(): Receipt = targetRuntime.submit(closeTargetEndpoint())

  def close(): Receipt = {
    val closed = Promise[Unit]()
    val receipt = sourceRuntime.submit {
      detachSource()
      closeTargetAfter(closed)
    }
    receipt.accepted.value.flatMap(_.failed.toOption) match {
      case None => new Receipt(receipt.id, Future.unit, closed.future)
      case Some(_) => receipt
    }
  }

  /** Close the target endpoint in a target turn admitted after every message this source sent. */
  private def closeTargetAfter(closed: Promise[Unit]): Unit = {
    val receipt = targetRuntime.submit {
      closeTargetEndpoint()
      closed.trySuccess(())
      ()
    }
    receipt.accepted.value.flatMap(_.failed.toOption).foreach { rejection =>
      if (targetRuntime.state eq RuntimeState.Open) closed.tryFailure(rejection) else closed.trySuccess(())
    }
  }
}

private[core] object BridgeLink {

  /** Create both endpoints and attach the source one, inside the source runtime. */
  def attach[A](source: Observable[A], targetRuntime: Runtime, write: Try[A] => Unit): BridgeLink[A] = {
    val sourceRuntime = source.runtime
    if (!targetRuntime.admitsSubmissions) {
      throw new IllegalArgumentException(
        s"A bridge delivers into a runtime that admits asynchronous submissions; ${targetRuntime.displayName} is a direct " +
          "runtime. Bridge into a scheduled or a dispatched runtime."
      )
    }
    sourceRuntime.run {
      val link = new BridgeLink[A](sourceRuntime, targetRuntime, write)
      source.addObserver(Observer.fromTry[A](link.send))(link.owner)
      link
    }
  }
}
