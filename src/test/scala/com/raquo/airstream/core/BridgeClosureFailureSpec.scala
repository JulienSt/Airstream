package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.RuntimeFailed
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.fixtures.ManualExecutor

import scala.collection.mutable
import scala.util.Success

/** An admitted bridge close must settle even when its runtime fails before executing the cleanup command. */
class BridgeClosureFailureSpec extends UnitSpec {

  it("fails an admitted bridge close when the source runtime fails before detaching its endpoint") {
    val sourceExecutor = new ManualExecutor
    val targetExecutor = new ManualExecutor
    val source = Runtime.dispatched(sourceExecutor)
    val target = Runtime.dispatched(targetExecutor)
    val reports = mutable.Buffer[Throwable]()
    source.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
    source.registerUnhandledErrorCallback(reports += _)
    val sourceBus = source.run(new EventBus[Int])
    val targetBus = target.run(new EventBus[Int])
    val bridge = source.run(EventBridge(sourceBus.events, targetBus))
    targetExecutor.runAll()

    try {
      val failure = new LinkageError("source failed before the admitted bridge close")
      source.submit(throw failure)
      val closing = bridge.close()
      closing.accepted.value shouldBe Some(Success(()))
      closing.completed.isCompleted shouldBe false

      sourceExecutor.runAll()

      source.state shouldBe RuntimeState.Failed
      reports.exists(_.isInstanceOf[RuntimeFailed]) shouldBe true
      closing.completed.value.exists(_.failed.toOption.exists(_.isInstanceOf[RuntimeFailed])) shouldBe true
    } finally {
      source.close()
      target.close()
      targetExecutor.runAll()
    }
  }

  it("fails an admitted bridge close when the target runtime fails before closing its endpoint") {
    val sourceExecutor = new ManualExecutor
    val targetExecutor = new ManualExecutor
    val source = Runtime.dispatched(sourceExecutor)
    val target = Runtime.dispatched(targetExecutor)
    val reports = mutable.Buffer[Throwable]()
    target.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
    target.registerUnhandledErrorCallback(reports += _)
    val sourceBus = source.run(new EventBus[Int])
    val targetBus = target.run(new EventBus[Int])
    val bridge = source.run(EventBridge(sourceBus.events, targetBus))
    targetExecutor.runAll()

    try {
      target.submit(throw new LinkageError("target failed before the admitted endpoint close"))
      val closing = bridge.close()
      sourceExecutor.runAll()
      closing.accepted.value shouldBe Some(Success(()))
      closing.completed.isCompleted shouldBe false

      targetExecutor.runAll()

      target.state shouldBe RuntimeState.Failed
      reports.exists(_.isInstanceOf[RuntimeFailed]) shouldBe true
      closing.completed.value.exists(_.failed.toOption.exists(_.isInstanceOf[RuntimeFailed])) shouldBe true
    } finally {
      source.close()
      sourceExecutor.runAll()
      target.close()
    }
  }
}
