package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/** Managed tasks on every platform, driven by an executor the spec pumps by hand: the result of a
  * task comes back through the runtime, where the scope decides whether it is still wanted, and every task settles with
  * exactly one of four outcomes.
  */
class TaskScopeSpec extends UnitSpec {

  /** An executor that only queues; the spec runs the queued work when it decides to. */
  final class PumpedExecutor extends ExecutionContext {
    private val queued = mutable.Queue[Runnable]()
    override def execute(runnable: Runnable): Unit = queued.enqueue(runnable)
    override def reportFailure(cause: Throwable): Unit = throw cause
    def runAll(): Int = {
      var count = 0
      while (queued.nonEmpty) {
        queued.dequeue().run()
        count += 1
      }
      count
    }
  }

  it("delivers a task's result inside the owning runtime and settles its outcome as delivered") {
    val runtime = Runtime.confined()
    val executor = new PumpedExecutor
    val seen = mutable.Buffer[(Try[Int], Runtime)]()
    val task = runtime.run {
      runtime.taskScope().launch(executor)(_ => 21 * 2)(result => seen += ((result, Runtime.current)))
    }
    seen.toList shouldBe Nil
    executor.runAll() shouldBe 1
    seen.toList shouldBe List((Success(42), runtime))
    task.outcome.value shouldBe Some(Success(TaskOutcome.Delivered))
  }

  it("suppresses the result of a task launched before a restart, raises its cancel signal, and delivers the new generation's") {
    val runtime = Runtime.confined()
    val executor = new PumpedExecutor
    val seen = mutable.Buffer[Try[Int]]()
    val scope = runtime.run(runtime.taskScope())
    val first = runtime.run(scope.launch(executor)(_ => 1)(seen += _))
    runtime.run(scope.restart())
    val second = runtime.run(scope.launch(executor)(_ => 2)(seen += _))
    first.isCancelRequested shouldBe true
    second.isCancelRequested shouldBe false
    executor.runAll() shouldBe 2
    seen.toList shouldBe List(Success(2))
    first.outcome.value shouldBe Some(Success(TaskOutcome.Suppressed))
    second.outcome.value shouldBe Some(Success(TaskOutcome.Delivered))
  }

  it("rejects a launch into a closed scope and suppresses a result that returns after the scope closed") {
    val runtime = Runtime.confined()
    val executor = new PumpedExecutor
    val seen = mutable.Buffer[Try[Int]]()
    val scope = runtime.run(runtime.taskScope())
    val inFlight = runtime.run(scope.launch(executor)(_ => 1)(seen += _))
    runtime.run(scope.close())
    val late = runtime.run(scope.launch(executor)(_ => 2)(seen += _))
    inFlight.isCancelRequested shouldBe true
    executor.runAll() shouldBe 1
    seen.toList shouldBe Nil
    inFlight.outcome.value shouldBe Some(Success(TaskOutcome.Suppressed))
    late.outcome.value.flatMap(_.toOption).exists(_.isInstanceOf[TaskOutcome.Rejected]) shouldBe true
  }

  it("delivers a failed computation's error and settles the task as failed, distinct from a delivered value") {
    val runtime = Runtime.confined()
    val executor = new PumpedExecutor
    val boom = new IllegalStateException("task failed")
    val seen = mutable.Buffer[Try[Int]]()
    val task = runtime.run(runtime.taskScope().launch[Int](executor)(_ => throw boom)(seen += _))
    executor.runAll()
    seen.toList shouldBe List(Failure(boom))
    task.outcome.value shouldBe Some(Success(TaskOutcome.Failed(boom)))
  }

  it("settles a task the executor refuses as rejected, with the executor's exception, and runs nothing") {
    val runtime = Runtime.confined()
    val refusal = new IllegalStateException("executor is full")
    val refusing = new ExecutionContext {
      override def execute(runnable: Runnable): Unit = throw refusal
      override def reportFailure(cause: Throwable): Unit = throw cause
    }
    val seen = mutable.Buffer[Try[Int]]()
    val task = runtime.run(runtime.taskScope().launch(refusing)(_ => 1)(seen += _))
    seen.toList shouldBe Nil
    task.outcome.value shouldBe Some(Success(TaskOutcome.Rejected(refusal)))
  }
}
