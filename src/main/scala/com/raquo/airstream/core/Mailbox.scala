package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.{RuntimeFailed, SubmissionRejected}
import com.raquo.airstream.platform.{AtomicCell, AtomicCounter, AtomicFlag, ConcurrentQueue}

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/** The admission and drain protocol of a scheduled runtime.
  *
  * Producers on any thread admit commands; one drainer at a time takes them in admission order and runs each as a turn
  * on the executor. The protocol keeps the following steps separate, because collapsing them is where the
  * classic defects hide:
  *
  *   - `admit` reserves capacity by compare-and-set, enqueues the command (the publication to the drainer) and settles
  *     the `accepted` promise; a command past capacity or after failure is rejected before any effect.
  *   - `claimDrain` is the one compare-and-set that makes a drain task the drainer; while the flag is set no second task
  *     is handed to the executor, so at most one thread ever runs this runtime's turns.
  *   - `takeNext` is the empty check: it either dequeues the next command or observes the mailbox empty.
  *   - `releaseDrain` clears the claim, and `recheck` right after it re-reads the queue: a command enqueued between the
  *     empty check and the release is not lost, because its producer saw the claim set and did not schedule, and the
  *     recheck now does.
  *   - A drain yields the executor after `turnsPerDrain` complete turns by handing itself over as a new task with the
  *     claim kept, so one busy runtime does not hold a pool thread indefinitely and no transaction is ever split.
  *
  * A close cuts admission off in the reservation counter, lets the drainer finish every accepted command, and then has the
  * drainer run the runtime's cleanup in one more serialized step before the runtime is `Closed`.
  *
  * When the executor rejects a drain task, at the first schedule or at a reschedule, the runtime fails:
  * admission closes, every queued receipt settles with `RuntimeFailed`, and a producer that enqueued at the same moment
  * settles its own command after it reads the failed state, so no accepted command is stranded.
  *
  * @param gate `runtime`'s gate, which builds this mailbox; the close's cleanup claims and retires its permit
  */
final private[airstream] class Mailbox(
  runtime: Runtime,
  gate: ManagedGate,
  executor: ExecutionContext,
  capacity: Int,
  turnsPerDrain: Int
) {

  import Mailbox.Command

  private val queue: ConcurrentQueue[Command] = new ConcurrentQueue[Command]

  private val lifecycle: AtomicCell[RuntimeState] = new AtomicCell[RuntimeState](RuntimeState.Open)

  /** How many admitted commands are not yet taken: reserved before the enqueue, so capacity is never exceeded. */
  private val reserved: AtomicCounter = new AtomicCounter

  /** Set while a drain task is scheduled or running: the single-drainer claim. */
  private val drainClaimed: AtomicFlag = new AtomicFlag

  private val cause: AtomicCell[Throwable] = new AtomicCell[Throwable](null)

  /** The promise of the close, once one was requested; the first request creates it, later ones share it. */
  private val closing: AtomicCell[Promise[Unit]] = new AtomicCell[Promise[Unit]](null)

  private val drainTask: Runnable = new Runnable {
    override def run(): Unit = drain()
  }

  /** The lifecycle state of the runtime this mailbox serves. */
  def state: RuntimeState = lifecycle.get

  /** Admit `body` as a command, or reject it. The returned receipt's `accepted` future is settled either way. */
  def submit(id: Long, body: () => Unit, reservedSlots: Int = 0): Receipt = {
    require(reservedSlots >= 0 && reservedSlots < capacity,
      s"reservedSlots must be between 0 and ${capacity - 1}, got $reservedSlots")
    val accepted = Promise[Unit]()
    val completed = Promise[Unit]()
    reserve(reservedSlots) match {
      case None =>
        val command = new Command(id, body, completed)
        enqueue(command)
        accepted.success(())
        if (lifecycle.get eq RuntimeState.Failed) {
          // Admitted while another thread was failing the runtime: its queue drain may have run before our enqueue.
          settleFailed(command)
        } else {
          scheduleDrain()
        }
      case Some(rejection) =>
        accepted.failure(rejection)
        completed.failure(rejection)
    }
    new Receipt(id, accepted.future, completed.future)
  }

  /** The admission linearisation point: reserve one slot when the runtime is open and the mailbox has room. The admission
    * cutoff of a close is a bit in the same counter, so a reservation and the cutoff are ordered by one compare-and-set: a
    * reservation that succeeds was admitted before the close, and none succeeds after it.
    */
  @tailrec private def reserve(reservedSlots: Int): Option[SubmissionRejected] = {
    val current = lifecycle.get
    val count = reserved.get
    if ((count & Mailbox.AdmissionClosed) != 0L) {
      val phase = if (current eq RuntimeState.Open) "closing" else current.toString.toLowerCase
      Some(SubmissionRejected(runtime, s"the runtime is $phase"))
    } else if (current ne RuntimeState.Open) {
      Some(SubmissionRejected(runtime, s"the runtime is ${current.toString.toLowerCase}"))
    } else {
      if (count >= capacity - reservedSlots) {
        Some(SubmissionRejected(runtime, s"the mailbox holds $count commands with $reservedSlots of its $capacity slots reserved"))
      } else if (reserved.compareAndSet(count, count + 1)) {
        None
      } else {
        reserve(reservedSlots)
      }
    }
  }

  /** Publish an admitted command to the drainer. */
  private def enqueue(command: Command): Unit = queue.offer(command)

  /** Become the drainer: true for exactly one caller while the claim is free. */
  private[airstream] def claimDrain(): Boolean = drainClaimed.compareAndSet(false, true)

  /** The empty check: the next command in admission order, or null when the mailbox is empty right now. */
  private[airstream] def takeNext(): Command = {
    val next = queue.poll()
    if (next ne null) {
      reserved.decrementAndGet()
    }
    next
  }

  /** Give the drain claim up. */
  private[airstream] def releaseDrain(): Unit = drainClaimed.set(false)

  /** Close the lost-wake-up window: a command admitted after the empty check gets its drain now, and so does a close
    * requested after it.
    */
  private[airstream] def recheck(): Unit = {
    if (!queue.isEmpty || isReadyToClose) {
      scheduleDrain()
    }
  }

  /** Request the graceful close: cut admission off, let the drainer finish the accepted commands,
    * then run the runtime's cleanup in one more serialized step. The future completes when that step is done; it fails
    * with `RuntimeFailed` when the runtime failed instead. Requesting it again returns the same future.
    */
  def close(): Future[Unit] = {
    val requested = Promise[Unit]()
    if (closing.compareAndSet(null, requested)) {
      cutAdmission()
      if (lifecycle.compareAndSet(RuntimeState.Open, RuntimeState.Closing)) {
        runtime.traceAdmissionClosed()
        scheduleDrain()
      } else {
        requested.tryFailure(RuntimeFailed(runtime, cause.get))
      }
    }
    closing.get.future
  }

  /** Set the admission cutoff bit; from now on no reservation succeeds. */
  @tailrec private def cutAdmission(): Unit = {
    val count = reserved.get
    if (!reserved.compareAndSet(count, count | Mailbox.AdmissionClosed)) {
      cutAdmission()
    }
  }

  /** True when the close can finish: requested, nothing queued, and no producer between its reservation and its enqueue. */
  private def isReadyToClose: Boolean = {
    (lifecycle.get eq RuntimeState.Closing) && (reserved.get & ~Mailbox.AdmissionClosed) == 0L && queue.isEmpty
  }

  /** On the drainer, holding the claim: run the runtime's cleanup, move to `Closed`, give the claim up, settle the close. */
  private def finishClose(): Unit = {
    runtime.closeResources(gate)
    lifecycle.compareAndSet(RuntimeState.Closing, RuntimeState.Closed)
    runtime.traceClosed()
    releaseDrain()
    closing.get.trySuccess(())
    ()
  }

  /** Hand a drain to the executor unless one is already claimed. */
  private def scheduleDrain(): Unit = {
    if (claimDrain()) {
      execute()
    }
  }

  /** Hand the drain task to the executor; a rejection fails the runtime. */
  private def execute(): Unit = {
    try {
      executor.execute(drainTask)
    } catch {
      case NonFatal(rejection) => fail(rejection)
    }
  }

  /** One drain: complete turns until the mailbox is empty or the budget is spent, then release or yield. */
  private def drain(): Unit = {
    var turns = 0
    var running = true
    while (running) {
      val next = takeNext()
      if ((next eq null) && isReadyToClose) {
        try {
          finishClose()
        } catch {
          case err: Throwable => fail(err)
        }
        running = false
      } else if (next eq null) {
        releaseDrain()
        recheck()
        running = false
      } else {
        try {
          runtime.executeTurn(next)
        } catch {
          case err: Throwable =>
            // A turn that could not claim the permit, left the engine torn or died of a fatal error: the runtime fails.
            settleFailed(next, err)
            fail(err)
            running = false
        }
        turns += 1
        if (running && turns >= turnsPerDrain) {
          // Yield with the claim kept: the next task continues the drain, nothing else can start one meanwhile.
          execute()
          running = false
        }
      }
    }
  }

  /** Move the runtime to `Failed` because of `err` and settle every queued receipt; idempotent. */
  private[airstream] def fail(err: Throwable): Unit = {
    cause.compareAndSet(null, err)
    if (
      lifecycle.compareAndSet(RuntimeState.Open, RuntimeState.Failed) ||
      lifecycle.compareAndSet(RuntimeState.Closing, RuntimeState.Failed)
    ) {
      runtime.reportFailure(cause.get)
    }
    val requested = closing.get
    if (requested ne null) {
      requested.tryFailure(RuntimeFailed(runtime, cause.get))
    }
    var next = queue.poll()
    while (next ne null) {
      settleFailed(next)
      next = queue.poll()
    }
  }

  private def settleFailed(command: Command): Unit = settleFailed(command, cause.get)

  private def settleFailed(command: Command, err: Throwable): Unit = {
    command.completed.tryFailure(RuntimeFailed(runtime, err))
    ()
  }
}

private[airstream] object Mailbox {

  /** One admitted command: its id, what to run, and the promise its turn settles. */
  final class Command(val id: Long, val body: () => Unit, val completed: Promise[Unit])

  /** The admission cutoff bit of the reservation counter: set by a close, it makes every later reservation fail. */
  val AdmissionClosed: Long = 1L << 62

}
