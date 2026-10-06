package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.fixtures.ManualExecutor

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.Success

/** Graceful closure on one thread, with the executor pumped by hand: admission ends
  * atomically, accepted commands still run and settle, cleanup runs once at a serialized point, late deliveries are
  * suppressed, and the runtime releases what it owns and nothing else.
  */
class RuntimeClosureSpec extends UnitSpec {

  it("closes a scheduled runtime gracefully: an accepted command still runs, a later submission is rejected, and the close completes after it") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor)
    val ran = mutable.Buffer[Int]()
    val accepted = runtime.submit(ran += 1)
    val closing = runtime.close()
    runtime.state shouldBe RuntimeState.Closing
    val late = runtime.submit(ran += 2)
    late.isAccepted shouldBe false
    closing.completed.isCompleted shouldBe false
    executor.runAll()
    ran.toList shouldBe List(1)
    accepted.completed.value shouldBe Some(Success(()))
    closing.completed.value shouldBe Some(Success(()))
    runtime.state shouldBe RuntimeState.Closed
  }

  /** An executor that only queues; the spec runs the queued work when it decides to. */
  final class PumpedExecutor extends ExecutionContext {
    private val queued = mutable.Queue[Runnable]()
    override def execute(runnable: Runnable): Unit = queued.enqueue(runnable)
    override def reportFailure(cause: Throwable): Unit = throw cause
    def runAll(): Unit = while (queued.nonEmpty) queued.dequeue().run()
  }

  it("closes the runtime's task scopes in its cleanup: a result returning afterwards is suppressed without an error report") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor)
    val workers = new PumpedExecutor
    val reported = mutable.Buffer[Throwable]()
    runtime.registerUnhandledErrorCallback(reported += _)
    val delivered = mutable.Buffer[Int]()
    val launched = mutable.Buffer[ManagedTask]()
    runtime.submit(launched += runtime.taskScope().launch(workers)(_ => 1)(delivered ++= _.toOption))
    runtime.close()
    executor.runAll()
    runtime.state shouldBe RuntimeState.Closed
    launched.head.isCancelRequested shouldBe true
    workers.runAll()
    delivered.toList shouldBe Nil
    launched.head.outcome.value shouldBe Some(Success(TaskOutcome.Suppressed))
    reported.toList shouldBe Nil
  }
}
