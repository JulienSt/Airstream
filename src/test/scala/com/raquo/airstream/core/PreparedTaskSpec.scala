package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.fixtures.ManualExecutor

import scala.util.{Failure, Success}

class PreparedTaskSpec extends UnitSpec {
  it("retains a prepared value when publication fails its runtime") {
    val commands = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    try {
      val submitted = runtime.submitResult {
        runtime.taskScope().launchPrepared(workers)(_ => 41)(input => input.get + 1)(_ => throw new LinkageError("publication failed"))
      }
      commands.runAll()
      val task = submitted.result.value.get.get
      workers.runAll()
      commands.runAll()
      runtime.state shouldBe RuntimeState.Failed
      task.prepared.value shouldBe Some(Success(Some(Success(42))))
    } finally {
      runtime.close()
      commands.runAll()
    }
  }

  it("never prepares a value from a scope closed before its computation returns") {
    val commands = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    try {
      val submitted = runtime.submitResult {
        val scope = runtime.taskScope()
        val result = scope.launchPrepared(workers)(_ => 42)(_ => fail("suppressed result prepared"))(_ => fail("suppressed result published"))
        scope.close()
        result
      }
      commands.runAll()
      val task = submitted.result.value.get.get
      workers.runAll()
      commands.runAll()
      task.prepared.value shouldBe Some(Success(None))
      task.computation.completed.value shouldBe Some(Success(TaskCompletion(TaskOutcome.Suppressed, Some(Success(42)))))
    } finally {
      runtime.close()
      commands.runAll()
    }
  }

  it("publishes a preparation failure without losing that failure's identity") {
    val commands = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    val problem = new IllegalStateException("preparation failed")
    try {
      val submitted = runtime.submitResult {
        runtime.taskScope().launchPrepared[Int, Int](workers)(_ => 42)(_ => throw problem)(value => value shouldBe Failure(problem))
      }
      commands.runAll()
      val task = submitted.result.value.get.get
      workers.runAll()
      commands.runAll()
      task.prepared.value shouldBe Some(Success(Some(Failure(problem))))
      runtime.state shouldBe RuntimeState.Open
    } finally {
      runtime.close()
      commands.runAll()
    }
  }

  it("settles preparation when a fatal preparation error fails its runtime") {
    val commands = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(commands)
    val problem = new LinkageError("fatal preparation failed")
    try {
      val submitted = runtime.submitResult {
        runtime.taskScope().launchPrepared[Int, Int](workers)(_ => 42)(_ => throw problem)(_ => fail("fatal preparation published"))
      }
      commands.runAll()
      val task = submitted.result.value.get.get
      workers.runAll()
      commands.runAll()
      task.prepared.value shouldBe Some(Success(Some(Failure(problem))))
      runtime.state shouldBe RuntimeState.Failed
    } finally {
      runtime.close()
      commands.runAll()
    }
  }
}
