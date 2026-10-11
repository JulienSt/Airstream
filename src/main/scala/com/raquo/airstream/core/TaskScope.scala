package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.{DeliveryRejected, SubmissionRejected}
import com.raquo.airstream.platform.{AtomicFlag, JsArray}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** A lifetime for external work whose results come back into one runtime.
  *
  * `launch` hands a computation to an executor the caller owns and delivers its result through the runtime, where the
  * scope decides whether the result is still wanted: only a task launched in the scope's current generation, while the
  * scope is open, reaches its `deliver` callback. `restart` begins a new generation and `close` ends the scope; a result of
  * an older generation or a closed scope is suppressed inside the runtime and never enters the new lifetime. Generations are internal: a caller sees tasks and their outcomes, never a generation number.
  *
  * Suppressing a delivery is not stopping a computation. A Scala `Future` or an arbitrary function cannot be cancelled in
  * general, so the scope promises nothing about the computation itself; it only raises the task's cancel signal, which a
  * computation that wants to stop early may poll.
  *
  * The scope owns no executor. The executor a task runs on belongs to the caller, is never shut down by the scope or by
  * the runtime's closure, and may be shared by any number of runtimes and scopes.
  *
  * Every method enters the runtime: call them from inside its turn, or from a thread a direct runtime admits.
  */
final class TaskScope private[airstream] (runtime: Runtime) {

  /** Schedule completion handling on the caller's executor after the task settles, including suppressed or refused delivery.
    * The returned future retains its result or failure so a caller can wait for cleanup before releasing that executor.
    *
    * @tparam A external computation result
    * @tparam B prepared value retained before publication
    * @tparam C completion handling result
    * @param executor caller-owned executor for work and completion handling
    * @param work external computation
    * @param prepare create an admitted value inside the delivery turn
    * @param publish apply the preparation result inside that turn
    * @param finish handle the retained computation and outcome once on the executor
    * @return preparation metadata and the completion handling result
    */
  def launchFinalized[A, B, C](executor: ExecutionContext)(work: CancelSignal => A)(prepare: Try[A] => B)(
    publish: Try[B] => Unit
  )(finish: TaskCompletion[A] => C): FinalizedTask[A, B, C] = {
    val finalized = Promise[C]()
    val preparation = launchPreparedNotifying(executor, Some { (completed: TaskCompletion[A]) =>
      try executor.execute(new Runnable {
        override def run(): Unit = {
          try finalized.success(finish(completed)) catch {
            case error: Throwable => finalized.tryFailure(error)
          }
          ()
        }
      }) catch {
        case NonFatal(rejection) => finalized.tryFailure(rejection)
      }
      ()
    })(work)(prepare)(publish)
    new FinalizedTask(preparation, finalized.future)
  }

  /** Prepare an admitted result inside its runtime and retain it before publishing graph changes. A publication failure
    * cannot hide a resource that preparation already returned; suppressed or refused work never reaches preparation.
    *
    * @tparam A external computation result
    * @tparam B prepared value retained independently of publication
    * @param executor caller-owned executor for external work
    * @param work computation run outside the graph
    * @param prepare create the value inside the admitted delivery turn
    * @param publish apply the prepared value or preparation failure inside that same turn
    * @return computation metadata and the preparation result, absent when delivery was suppressed or refused
    */
  def launchPrepared[A, B](executor: ExecutionContext)(work: CancelSignal => A)(prepare: Try[A] => B)(
    publish: Try[B] => Unit
  ): PreparedTask[A, B] = launchPreparedNotifying(executor, None)(work)(prepare)(publish)

  private def launchPreparedNotifying[A, B](executor: ExecutionContext, notify: Option[TaskCompletion[A] => Unit])(
    work: CancelSignal => A
  )(prepare: Try[A] => B)(publish: Try[B] => Unit): PreparedTask[A, B] = {
    val prepared = Promise[Try[B]]()
    val computation = launchResultNotifying(executor, notify)(work) { input =>
      val value = try Try(prepare(input)) catch {
        case error: Throwable =>
          prepared.trySuccess(Failure(error))
          throw error
      }
      prepared.success(value)
      publish(value)
    }
    val completed: Future[Option[Try[B]]] = computation.task.outcome.flatMap {
      case TaskOutcome.Delivered | _: TaskOutcome.Failed => prepared.future.map(Some(_))(ExecutionContext.parasitic)
      case _ => Future.successful(None)
    }(ExecutionContext.parasitic)
    new PreparedTask(computation, completed)
  }

  /** Retain a computation's value together with its delivery outcome, including when its runtime cannot accept it.
    * This lets callers finish releasing resources after the graph has closed without rerunning the computation.
    *
    * @tparam A the computation's result
    * @param executor caller-owned executor that runs the computation
    * @param work computation whose returned value or failure is retained
    * @param deliver callback run only when the scope admits the result
    * @return the managed task and completion metadata; the result is absent when work never ran
    */
  def launchResult[A](executor: ExecutionContext)(work: CancelSignal => A)(deliver: Try[A] => Unit): ManagedResult[A] =
    launchResultNotifying(executor, None)(work)(deliver)

  private def launchResultNotifying[A](executor: ExecutionContext, notify: Option[TaskCompletion[A] => Unit])(
    work: CancelSignal => A
  )(deliver: Try[A] => Unit): ManagedResult[A] = {
    val computed = Promise[Try[A]]()
    val callback = notify.map(handler => (outcome: TaskOutcome) => handler(TaskCompletion(outcome, computed.future.value.flatMap(_.toOption))))
    val task = launchNotifying(executor, callback) { signal =>
      val result = Try(work(signal))
      computed.success(result)
      result.get
    }(deliver)
    val completed = task.outcome.map { outcome =>
      TaskCompletion(outcome, computed.future.value.flatMap(_.toOption))
    }(ExecutionContext.parasitic)
    new ManagedResult(task, completed)
  }

  // Runtime state, touched only under the permit.

  /** The current generation; a task carries the one it was launched in. 64-bit and never reused. */
  private var generation: Long = 1L

  private var open: Boolean = true

  /** The tasks of the current generation whose result has not come back yet, to signal when the generation ends. */
  private val running: JsArray[ManagedTask] = JsArray()

  /** Run `work` on `executor` and deliver its result to `deliver` inside this scope's runtime, if the task is still
    * current by then. `work` receives the task's cancel signal; `deliver` receives the result or the failure `work` threw.
    *
    * @return the task, whose outcome settles once: `Delivered` or `Failed` when the result reached `deliver`, `Suppressed`
    *   when the scope moved on or closed first, `Rejected` when the scope, the executor or the runtime refused the task
    */
  def launch[A](executor: ExecutionContext)(work: CancelSignal => A)(deliver: Try[A] => Unit): ManagedTask =
    launchNotifying(executor, None)(work)(deliver)

  private def launchNotifying[A](executor: ExecutionContext, notify: Option[TaskOutcome => Unit])(
    work: CancelSignal => A
  )(deliver: Try[A] => Unit): ManagedTask = runtime.run {
    val task = new ManagedTask(runtime.nextTaskId(), notify)
    if (!open) {
      task.settle(TaskOutcome.Rejected(SubmissionRejected(runtime, "the task scope is closed")))
    } else {
      val launchedIn = generation
      running.push(task)
      try {
        executor.execute(new Runnable {
          override def run(): Unit = returnResult(task, launchedIn, Try(work(task)), deliver)
        })
      } catch {
        case NonFatal(rejection) =>
          forget(task)
          task.settle(TaskOutcome.Rejected(rejection))
      }
    }
    task
  }

  /** Begin a new generation: every task launched before is asked to cancel and its result will be suppressed. */
  def restart(): Unit = runtime.run {
    generation += 1
    abandonRunning()
  }

  /** Ask every task of the generation that just ended to cancel; their results will find a newer generation or a closed
    * scope and be suppressed.
    */
  private def abandonRunning(): Unit = {
    running.forEach(_.requestCancel())
    running.length = 0
  }

  /** End the scope: running tasks are asked to cancel, their results are suppressed, and `launch` is rejected. */
  def close(): Unit = runtime.run {
    open = false
    abandonRunning()
    runtime.forgetScope(this)
  }

  /** On the worker: hand the result to the runtime. Nothing here touches the scope; the decision is the runtime's. */
  private def returnResult[A](task: ManagedTask, launchedIn: Long, result: Try[A], deliver: Try[A] => Unit): Unit = {
    val receipt = runtime.submit(applyResult(task, launchedIn, result, deliver))
    receipt.accepted.value.flatMap(_.failed.toOption) match {
      case Some(rejection) =>
        if (runtime.isShutDown) {
          // The runtime closed on purpose and its close closed this scope: a late result is suppressed.
          runtime.traceTaskSuppressed(task.id)
          task.settle(TaskOutcome.Suppressed)
        } else {
          rejectResult(task, result, rejection)
        }
      case None =>
        // An admitted result can still be discarded when the runtime fails before its delivery turn.
        receipt.completed.failed.foreach { rejection =>
          if (task.outcome.value.isEmpty) {
            rejectResult(task, result, rejection)
          }
        }(ExecutionContext.parasitic)
    }
  }

  /** The runtime refused the result while open (a full mailbox, a failed runtime, a direct runtime the worker may not
    * enter): the result never reached the graph, and a failure it carried must not vanish with it.
    * Reported before the outcome settles, so whoever waits for the outcome finds the report already made.
    */
  private def rejectResult[A](task: ManagedTask, result: Try[A], rejection: Throwable): Unit = {
    val report = DeliveryRejected(runtime, rejection)
    result.failed.foreach(report.addSuppressed)
    runtime.sendUnhandledError(report)
    task.settle(TaskOutcome.Rejected(rejection))
  }

  /** Inside the runtime: deliver the result when its generation is still the scope's, suppress it otherwise. */
  private def applyResult[A](task: ManagedTask, launchedIn: Long, result: Try[A], deliver: Try[A] => Unit): Unit = {
    if (open && launchedIn == generation) {
      forget(task)
      runtime.traceTaskDelivered(task.id)
      val outcome = result match {
        case Success(_) => TaskOutcome.Delivered
        case Failure(err) => TaskOutcome.Failed(err)
      }
      try {
        deliver(result)
      } catch {
        case NonFatal(err) => runtime.sendUnhandledError(err)
      } finally {
        // Completion handling can run inline: retain prepared resources and finish publication before handing them to cleanup.
        task.settle(outcome)
      }
    } else {
      runtime.traceTaskSuppressed(task.id)
      task.settle(TaskOutcome.Suppressed)
    }
  }

  private def forget(task: ManagedTask): Unit = {
    val index = running.indexOf(task)
    if (index >= 0) {
      running.splice(index, deleteCount = 1)
    }
  }
}

/** Retain a task's computation independently of its graph delivery, so refused or suppressed resources remain reportable.
  *
  * @tparam A the computation's result
  * @param task the task's cancellation handle and delivery outcome
  * @param completed computation metadata once the delivery outcome has settled
  */
final class ManagedResult[+A] private[airstream] (
  val task: ManagedTask,
  val completed: Future[TaskCompletion[A]]
)

/** Preserve preparation before publication, allowing cleanup after a publication error closes or fails the runtime.
  *
  * @tparam A external computation result
  * @tparam B value produced inside the delivery turn
  * @param computation external work and its delivery classification
  * @param prepared preparation value or failure, absent if the scope never admitted delivery
  */
final class PreparedTask[+A, +B] private[airstream] (
  val computation: ManagedResult[A],
  val prepared: Future[Option[Try[B]]]
)

/** Retain completion handling independently of graph observation, so cleanup can finish after the runtime closes.
  *
  * @tparam A external computation result
  * @tparam B prepared value
  * @tparam C completion handling result
  * @param preparation computation and admitted preparation metadata
  * @param finalized completion handling result or failure after its executor job finishes
  */
final class FinalizedTask[+A, +B, +C] private[airstream] (
  val preparation: PreparedTask[A, B],
  val finalized: Future[C]
)

/** Report what a task computed and how its scope handled delivery. A missing result means work never ran.
  *
  * @tparam A the computation's result
  * @param outcome the task's delivery classification
  * @param result the returned value or computation failure, when work ran
  */
final case class TaskCompletion[+A](outcome: TaskOutcome, result: Option[Try[A]])

/** What a managed computation may poll to learn that nobody wants its result any more. */
trait CancelSignal {

  /** True once the task's scope restarted or closed; a computation may stop early, nothing forces it to. */
  def isCancelRequested: Boolean
}

/** One task launched in a [[TaskScope]]: its id, never reused within its runtime, and how it ended.
  *
  * @param id identifier unique within the runtime
  * @param onSettled optional internal notification invoked only by the first outcome settlement
  */
final class ManagedTask private[airstream] (val id: Long, onSettled: Option[TaskOutcome => Unit] = None) extends CancelSignal {

  /** Raised by the runtime, read by the computation on its own thread. */
  private val cancelRequested: AtomicFlag = new AtomicFlag

  private val settled: Promise[TaskOutcome] = Promise[TaskOutcome]()

  override def isCancelRequested: Boolean = cancelRequested.get

  /** Settles once, with the task's [[TaskOutcome]]. */
  def outcome: Future[TaskOutcome] = settled.future

  private[core] def requestCancel(): Unit = cancelRequested.set(true)

  private[core] def settle(outcome: TaskOutcome): Unit = {
    if (settled.trySuccess(outcome)) onSettled.foreach(_(outcome))
    ()
  }
}

/** How a managed task ended: four distinct outcomes. */
sealed abstract class TaskOutcome

object TaskOutcome {

  /** The computation returned and its value reached `deliver` in the task's own generation. */
  case object Delivered extends TaskOutcome

  /** The computation threw, and the failure reached `deliver` in the task's own generation. */
  final case class Failed(cause: Throwable) extends TaskOutcome

  /** The result arrived after the scope restarted or closed, or after the runtime closed; `deliver` was not called. */
  case object Suppressed extends TaskOutcome

  /** The scope was closed at launch, the executor refused the computation, or the runtime could not admit the result's
    * delivery; `cause` says which.
    */
  final case class Rejected(cause: Throwable) extends TaskOutcome
}
