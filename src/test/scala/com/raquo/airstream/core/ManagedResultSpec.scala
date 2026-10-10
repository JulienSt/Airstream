package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.fixtures.ManualExecutor

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class ManagedResultSpec extends UnitSpec {
  it("retains a suppressed computation's value after the runtime closes") {
    val commands = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    val submitted = runtime.submitResult {
      val scope = runtime.taskScope()
      val result = scope.launchResult(workers)(_ => 42)(_ => fail("closed task delivered"))
      scope.close()
      result
    }
    commands.runAll()
    val result = submitted.result.value.get.get
    runtime.close()
    commands.runAll()
    workers.runAll()
    result.completed.value shouldBe Some(Success(TaskCompletion(TaskOutcome.Suppressed, Some(Success(42)))))
  }

  it("reports absent computation when its executor refuses to run work") {
    val commands = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    val problem = new IllegalStateException("executor refused work")
    val executor = new ExecutionContext {
      override def execute(command: Runnable): Unit = throw problem
      override def reportFailure(error: Throwable): Unit = fail("unexpected failure report")
    }
    try {
      val submitted = runtime.submitResult {
        runtime.taskScope().launchResult(executor)(_ => fail("refused work ran"))(_ => fail("refused work delivered"))
      }
      commands.runAll()
      submitted.result.value.get.get.completed.value shouldBe
        Some(Success(TaskCompletion(TaskOutcome.Rejected(problem), None)))
    } finally {
      runtime.close()
      commands.runAll()
    }
  }

  it("retains a computation failure along with its delivery outcome") {
    val commands = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    val problem = new IllegalArgumentException("computation failed")
    try {
      val submitted = runtime.submitResult {
        runtime.taskScope().launchResult[Int](workers)(_ => throw problem)(result => result shouldBe Failure(problem))
      }
      commands.runAll()
      workers.runAll()
      commands.runAll()
      submitted.result.value.get.get.completed.value shouldBe
        Some(Success(TaskCompletion(TaskOutcome.Failed(problem), Some(Failure(problem)))))
    } finally {
      runtime.close()
      commands.runAll()
    }
  }
}
