package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.{DeliveryRejected, RuntimeFailed}
import com.raquo.airstream.fixtures.ManualExecutor

import scala.collection.mutable
import scala.util.Success

/** An accepted task delivery still settles when its runtime fails before applying the result. */
class TaskScopeFailureSpec extends UnitSpec {

  it("rejects a task whose admitted result cannot run after the owning runtime fails") {
    val executor = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(executor)
    val delivered = mutable.Buffer[Int]()
    val reports = mutable.Buffer[Throwable]()
    runtime.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
    runtime.registerUnhandledErrorCallback(reports += _)
    val task = runtime.run(runtime.taskScope().launch(workers)(_ => 42)(delivered ++= _.toOption))

    try {
      runtime.submit(throw new LinkageError("runtime failed before the admitted task result"))
      workers.runAll()
      task.outcome.isCompleted shouldBe false

      executor.runAll()

      runtime.state shouldBe RuntimeState.Failed
      delivered.toList shouldBe Nil
      task.outcome.value.exists {
        case Success(TaskOutcome.Rejected(_: RuntimeFailed)) => true
        case _ => false
      } shouldBe true
      reports.count(_.isInstanceOf[DeliveryRejected]) shouldBe 1
    } finally {
      runtime.close()
    }
  }

  it("retains a delivered task outcome when its delivery callback fatally fails the runtime") {
    val executor = new ManualExecutor
    val workers = new ManualExecutor
    val runtime = Runtime.dispatched(executor)
    val delivered = mutable.Buffer[Int]()
    val reports = mutable.Buffer[Throwable]()
    runtime.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
    runtime.registerUnhandledErrorCallback(reports += _)
    val task = runtime.run {
      runtime.taskScope().launch(workers)(_ => 42) { result =>
        delivered ++= result.toOption
        throw new LinkageError("delivery ran before its callback failed")
      }
    }

    try {
      workers.runAll()
      executor.runAll()

      runtime.state shouldBe RuntimeState.Failed
      delivered.toList shouldBe List(42)
      task.outcome.value shouldBe Some(Success(TaskOutcome.Delivered))
      reports.count(_.isInstanceOf[DeliveryRejected]) shouldBe 0
    } finally {
      runtime.close()
    }
  }
}
