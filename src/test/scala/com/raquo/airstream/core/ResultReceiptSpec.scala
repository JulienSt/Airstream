package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.fixtures.ManualExecutor
import com.raquo.airstream.core.AirstreamError.{RuntimeFailed, SubmissionRejected}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

class ResultReceiptSpec extends UnitSpec {
  it("retains a scheduled command's value after running it in its owning runtime") {
    val executor = new ManualExecutor
    val runtime = Runtime.dispatched(executor)
    try {
      val submitted = runtime.submitResult((42, Runtime.current))
      submitted.receipt.isAccepted shouldBe true
      submitted.result.value shouldBe None

      executor.runAll()

      submitted.receipt.completed.value shouldBe Some(Success(()))
      submitted.result.value shouldBe Some(Success((42, runtime)))
    } finally {
      runtime.close()
      executor.runAll()
    }
  }

  it("does not expose a produced value before its receipt settles") {
    val executor = new ManualExecutor
    val runtime = Runtime.dispatched(executor)
    val observed = mutable.Buffer[Option[Try[Int]]]()
    val submitted = runtime.submitResult(42)
    runtime.traceTo(new RuntimeTrace {
      override def turnCompleted(runtime: Runtime, commandId: Long): Unit =
        observed += submitted.result.value
    })
    try {
      executor.runAll()
      observed.toList shouldBe List(None)
      submitted.result.value shouldBe Some(Success(42))
    } finally {
      runtime.close()
      executor.runAll()
    }
  }

  it("retains admission failure without evaluating a refused value command") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor, capacity = 1, turnsPerDrain = 1)
    var ran = false
    try {
      runtime.submit(())
      val refused = runtime.submitResult { ran = true; 42 }
      refused.receipt.isAccepted shouldBe false
      refused.result.value.exists {
        case Failure(_: SubmissionRejected) => true
        case _ => false
      } shouldBe true
      executor.runAll()
      ran shouldBe false
    } finally {
      runtime.close()
      executor.runAll()
    }
  }

  it("returns the command's failure through its value future") {
    val executor = new ManualExecutor
    val runtime = Runtime.dispatched(executor)
    val problem = new IllegalStateException("value command failed")
    try {
      val submitted = runtime.submitResult[Int](throw problem)
      executor.runAll()
      submitted.result.value shouldBe Some(Failure(problem))
      submitted.receipt.completed.value shouldBe Some(Failure(problem))
      runtime.state shouldBe RuntimeState.Open
    } finally {
      runtime.close()
      executor.runAll()
    }
  }

  it("settles a value command discarded when its runtime fails before executing it") {
    val executor = new ManualExecutor
    val runtime = Runtime.dispatched(executor)
    val reports = mutable.Buffer[Throwable]()
    var ran = false
    runtime.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
    runtime.registerUnhandledErrorCallback(reports += _)
    try {
      runtime.submit(throw new LinkageError("runtime failed before value command"))
      val submitted = runtime.submitResult { ran = true; 42 }
      submitted.receipt.isAccepted shouldBe true
      executor.runAll()
      submitted.result.value.exists {
        case Failure(_: RuntimeFailed) => true
        case _ => false
      } shouldBe true
      ran shouldBe false
      runtime.state shouldBe RuntimeState.Failed
      reports.count(_.isInstanceOf[RuntimeFailed]) shouldBe 1
    } finally {
      runtime.close()
      executor.runAll()
    }
  }
}
