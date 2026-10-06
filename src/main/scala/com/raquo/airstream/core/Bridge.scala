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
  def closeTarget(): Receipt = link.target.close()

  /** Close the whole bridge: the source endpoint is detached in a source turn, then the target endpoint is closed in a
    * target turn after every message the source sent before; the receipt completes when both are done.
    */
  def close(): Receipt = link.source.closeBoth()
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
    val link = BridgeLink.attach[A](source, new BusEndpoint[A](target))
    source.runtime.run(new EventBridge(link))
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
    val link = BridgeLink.attach[A](source, new VarEndpoint[A](target))
    source.runtime.run(new SignalBridge(link))
  }
}

/** The two endpoints of one bridge. Each belongs to one runtime and is touched only in that runtime's turns; what crosses
  * between them is a sequence number with an immutable value, handed over through the target runtime's `submit`.
  */
final private[core] class BridgeLink[A](val source: SourceEndpoint[A], val target: TargetEndpoint[A])

private[core] object BridgeLink {

  /** Create both endpoints and attach the source one, inside the source runtime. */
  def attach[A](observable: Observable[A], target: TargetEndpoint[A]): BridgeLink[A] = {
    val sourceRuntime = observable.runtime
    if (!target.runtime.admitsSubmissions) {
      throw new IllegalArgumentException(
        s"A bridge delivers into a runtime that admits asynchronous submissions; ${target.runtime.displayName} is a direct " +
          "runtime. Bridge into a scheduled or a dispatched runtime."
      )
    }
    sourceRuntime.run {
      val source = new SourceEndpoint[A](sourceRuntime, target)
      source.attachTo(observable)
      new BridgeLink[A](source, target)
    }
  }
}

/** The source side of a bridge: an observer of the source, the next sequence number, and the decision to stop sending.
  * Its fields belong to the source runtime; `detached` is also read by a full close called from any thread.
  */
final private[core] class SourceEndpoint[A](sourceRuntime: Runtime, target: TargetEndpoint[A]) {

  private val owner: ManualOwner = new ManualOwner

  private var nextSequence: Long = 0L

  private val detached: AtomicFlag = new AtomicFlag

  /** In the source turn that creates the bridge: hold this endpoint, have the target hold its own before any message, and
    * observe the source, whose initial notification (for a signal) is the snapshot.
    */
  def attachTo(observable: Observable[A]): Unit = {
    sourceRuntime.holdBridgeSource(this)
    // Admitted before the snapshot. A refusal needs no handling here: the snapshot is refused the same way.
    target.hold()
    observable.addObserver(Observer.fromTry[A](send))(owner)
  }

  /** In the source turn: number the notification and hand it to the target runtime. */
  private def send(value: Try[A]): Unit = {
    if (!detached.get) {
      val sequence = nextSequence
      nextSequence += 1
      target.offer(sequence, value).foreach(refused)
    }
  }

  /** The target refused a message: the order would have a gap, so the bridge stops sending. A target that is closing or
    * closed refused on purpose; any other refusal (a full mailbox, a failed runtime) is reported to the source runtime.
    */
  private def refused(rejection: Throwable): Unit = {
    sourceRuntime.run(detach())
    if (target.runtime.state eq RuntimeState.Open) {
      sourceRuntime.sendUnhandledError(DeliveryRejected(target.runtime, rejection))
    }
  }

  /** In a source turn: stop observing the source. Idempotent. */
  def detach(): Unit = {
    if (detached.compareAndSet(false, true)) {
      sourceRuntime.forgetBridgeSource(this)
      owner.killSubscriptions()
    }
  }

  /** The full close: detach in a source turn, then close the target in a target turn admitted after every message sent. */
  def closeBoth(): Receipt = {
    val closed = Promise[Unit]()
    val receipt = sourceRuntime.submit(detachThenCloseTarget(closed))
    receipt.accepted.value.flatMap(_.failed.toOption) match {
      case None => new Receipt(receipt.id, Future.unit, closed.future)
      case Some(_) if detached.get =>
        // The source runtime closed and released this endpoint after its last turn, so every message it sent is already
        // admitted at the target; only the target endpoint is left.
        target.closeAfter(closed)
        new Receipt(receipt.id, Future.unit, closed.future)
      case Some(_) => receipt
    }
  }

  private def detachThenCloseTarget(closed: Promise[Unit]): Unit = {
    detach()
    target.closeAfter(closed)
  }
}

/** The target side of a bridge: the next sequence number it expects, the messages that arrived early, and whether it is
  * closed. Its fields belong to the target runtime; every method that touches them runs in a target turn it submitted.
  */
sealed abstract private[core] class TargetEndpoint[A](val runtime: Runtime) {

  private var expected: Long = 0L

  private val early: mutable.HashMap[Long, Try[A]] = mutable.HashMap.empty

  private val closed: AtomicFlag = new AtomicFlag

  /** Apply one value in the target turn, at its root, so it starts a new target transaction. */
  protected def write(value: Try[A]): Unit

  /** From the source turn: have the target runtime hold this endpoint, so its close can release it. */
  def hold(): Unit = {
    runtime.submit(holdInside())
    ()
  }

  private def holdInside(): Unit = {
    if (!closed.get) {
      runtime.holdBridgeTarget(this)
    }
  }

  /** From the source turn: hand a message over; the refusal, when the target runtime did not admit it. */
  def offer(sequence: Long, value: Try[A]): Option[Throwable] = {
    runtime.submit(receive(sequence, value)).accepted.value.flatMap(_.failed.toOption)
  }

  /** In a target turn: apply the message when it is the next one, buffer it when it arrived early. */
  private[core] def receive(sequence: Long, value: Try[A]): Unit = {
    if (!closed.get) {
      early.update(sequence, value)
      applyInOrder()
    }
  }

  private def applyInOrder(): Unit = {
    var next = early.remove(expected)
    while (next.isDefined) {
      val sequence = expected
      expected += 1
      runtime.traceBridgeMessageDelivered(sequence)
      try {
        write(next.get)
      } catch {
        case NonFatal(err) => runtime.sendUnhandledError(err)
      }
      next = early.remove(expected)
    }
  }

  /** The target-close acknowledgement: a target turn after which nothing is applied. */
  def close(): Receipt = runtime.submit(closeInside())

  /** Close in a target turn and settle `done` there; a target that already closed or is closing counts as closed. */
  def closeAfter(done: Promise[Unit]): Unit = {
    val receipt = runtime.submit(closeAndSettle(done))
    receipt.accepted.value.flatMap(_.failed.toOption).foreach { rejection =>
      if (runtime.state eq RuntimeState.Open) done.tryFailure(rejection) else done.trySuccess(())
    }
  }

  private def closeAndSettle(done: Promise[Unit]): Unit = {
    closeInside()
    done.trySuccess(())
    ()
  }

  /** In a target turn: apply nothing any more and drop what is buffered. Idempotent. */
  def closeInside(): Unit = {
    if (closed.compareAndSet(false, true)) {
      runtime.forgetBridgeTarget(this)
      early.clear()
    }
  }
}

/** A target endpoint that emits on an event bus. */
final private[core] class BusEndpoint[A](bus: EventBus[A]) extends TargetEndpoint[A](bus.events.runtime) {

  override protected def write(value: Try[A]): Unit = bus.emitTry(value)
}

/** A target endpoint that sets a Var. */
final private[core] class VarEndpoint[A](variable: Var[A]) extends TargetEndpoint[A](variable.runtime) {

  override protected def write(value: Try[A]): Unit = variable.setTry(value)
}
