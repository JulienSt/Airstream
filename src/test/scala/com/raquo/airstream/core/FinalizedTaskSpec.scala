package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.fixtures.ManualExecutor

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class FinalizedTaskSpec extends UnitSpec {
  it("finishes admitted work after preparation and publication with an inline executor") {
    val commands = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    val phases = scala.collection.mutable.Buffer[String]()
    val workers = new ExecutionContext {
      override def execute(command: Runnable): Unit = command.run()
      override def reportFailure(error: Throwable): Unit = fail("unexpected executor failure", error)
    }
    try {
      val submitted = runtime.submitResult {
        runtime.taskScope().launchFinalized(workers)(_ => 41) { input =>
          phases += "prepare"
          input.get + 1
        } { prepared =>
          prepared shouldBe Success(42)
          phases += "publish"
        } { completed =>
          phases += "finish"
          completed
        }
      }
      commands.runAll()
      val task = submitted.result.value.get.get
      phases.toList shouldBe List("prepare", "publish", "finish")
      task.finalized.value shouldBe Some(Success(TaskCompletion(TaskOutcome.Delivered, Some(Success(41)))))
      task.preparation.prepared.value shouldBe Some(Success(Some(Success(42))))
    } finally {
      runtime.close()
      commands.runAll()
    }
  }

  it("finishes suppressed work on its executor after the runtime closes") {
    val commands = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    val submitted = runtime.submitResult {
      val scope = runtime.taskScope()
      val task = scope.launchFinalized(workers)(_ => 42)(_ => fail("suppressed work prepared"))(_ => fail("suppressed work published")) {
        completed =>
          (Runtime.current eq runtime) shouldBe false
          completed
      }
      scope.close()
      task
    }
    commands.runAll()
    val task = submitted.result.value.get.get
    runtime.close()
    commands.runAll()
    workers.runAll()
    task.finalized.value shouldBe Some(Success(TaskCompletion(TaskOutcome.Suppressed, Some(Success(42)))))
  }

  it("finishes once even when a settled task receives another outcome") {
    val commands = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    var finishes = 0
    try {
      val submitted = runtime.submitResult {
        runtime.taskScope().launchFinalized(workers)(_ => 42)(_.get)(_ => ()) { completed =>
          finishes += 1
          completed.result.get.get
        }
      }
      commands.runAll()
      val task = submitted.result.value.get.get
      workers.runAll()
      commands.runAll()
      task.finalized.value shouldBe None
      task.preparation.computation.task.settle(TaskOutcome.Suppressed)
      workers.runAll()
      finishes shouldBe 1
      task.finalized.value shouldBe Some(Success(42))
    } finally {
      runtime.close()
      commands.runAll()
    }
  }

  it("retains a completion handling failure") {
    val commands = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    val problem = new IllegalArgumentException("cleanup failed")
    try {
      val submitted = runtime.submitResult {
        runtime.taskScope().launchFinalized(workers)(_ => 42)(_.get)(_ => ())(_ => throw problem)
      }
      commands.runAll()
      val task = submitted.result.value.get.get
      workers.runAll()
      commands.runAll()
      workers.runAll()
      task.finalized.value shouldBe Some(Failure(problem))
      runtime.state shouldBe RuntimeState.Open
    } finally {
      runtime.close()
      commands.runAll()
    }
  }

  it("settles completion handling when the executor refuses it") {
    val commands = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    val problem = new IllegalStateException("executor refused work")
    val executor = new ExecutionContext {
      override def execute(command: Runnable): Unit = throw problem
      override def reportFailure(error: Throwable): Unit = fail("unexpected failure report")
    }
    try {
      val submitted = runtime.submitResult {
        runtime.taskScope().launchFinalized(executor)(_ => fail("refused work ran"))(_ => fail("refused work prepared"))(_ => ())(
          _ => fail("refused completion handling ran"))
      }
      commands.runAll()
      submitted.result.value.get.get.finalized.value shouldBe Some(Failure(problem))
    } finally {
      runtime.close()
      commands.runAll()
    }
  }
}
