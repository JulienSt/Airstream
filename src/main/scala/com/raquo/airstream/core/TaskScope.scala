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
  def launch[A](executor: ExecutionContext)(work: CancelSignal => A)(deliver: Try[A] => Unit): ManagedTask = runtime.run {
    val task = new ManagedTask(runtime.nextTaskId())
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
      task.settle(result match {
        case Success(_) => TaskOutcome.Delivered
        case Failure(err) => TaskOutcome.Failed(err)
      })
      try {
        deliver(result)
      } catch {
        case NonFatal(err) => runtime.sendUnhandledError(err)
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

/** What a managed computation may poll to learn that nobody wants its result any more. */
trait CancelSignal {

  /** True once the task's scope restarted or closed; a computation may stop early, nothing forces it to. */
  def isCancelRequested: Boolean
}

/** One task launched in a [[TaskScope]]: its id, never reused within its runtime, and how it ended. */
final class ManagedTask private[airstream] (val id: Long) extends CancelSignal {

  /** Raised by the runtime, read by the computation on its own thread. */
  private val cancelRequested: AtomicFlag = new AtomicFlag

  private val settled: Promise[TaskOutcome] = Promise[TaskOutcome]()

  override def isCancelRequested: Boolean = cancelRequested.get

  /** Settles once, with the task's [[TaskOutcome]]. */
  def outcome: Future[TaskOutcome] = settled.future

  private[core] def requestCancel(): Unit = cancelRequested.set(true)

  private[core] def settle(outcome: TaskOutcome): Unit = {
    settled.trySuccess(outcome)
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
