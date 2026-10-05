package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.{RuntimeFailed, SubmissionRejected}
import com.raquo.airstream.fixtures.ManualExecutor
import com.raquo.airstream.state.Var

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** The drain protocol exercised one scheduling step at a time: the
  * lost-wake-up window between the empty check and the release is closed by the recheck, an executor rejection at the
  * first drain or at a reschedule fails the runtime and settles every accepted receipt, and a drain yields after a bounded
  * number of complete turns without splitting one.
  */
class MailboxSpec extends UnitSpec {

  /** An executor that rejects the given `execute` calls, counted from one, and queues the others for the test to run. */
  private final class RejectingExecutor(rejects: Set[Int]) extends ExecutionContext {
    val delegate = new ManualExecutor
    var calls = 0
    override def execute(task: Runnable): Unit = {
      calls += 1
      if (rejects.contains(calls)) {
        throw new IllegalStateException(s"execute call $calls rejected")
      }
      delegate.execute(task)
    }
    override def reportFailure(cause: Throwable): Unit = throw cause
  }

  it("does not lose a command admitted between the drainer's empty check and its release: the recheck schedules the drain") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor)
    val mailbox = runtime.mailbox
    val ran = mutable.Buffer[String]()
    runtime.submit(ran += "first")
    executor.executions shouldBe 1 // the producer claimed the drain and scheduled it
    // The drainer's steps, driven by hand instead of through the executor's task:
    val first = mailbox.takeNext()
    runtime.executeTurn(first)
    ran.toList shouldBe List("first")
    (mailbox.takeNext() eq null) shouldBe true // empty check: the mailbox is observed empty
    val late = runtime.submit(ran += "late") // a producer admits now: the claim is still set, so it schedules nothing
    late.isAccepted shouldBe true
    executor.executions shouldBe 1
    mailbox.releaseDrain() // release: a drainer that stopped here would strand `late`
    executor.executions shouldBe 1
    mailbox.recheck() // the recheck sees the late command and takes the claim back
    executor.executions shouldBe 2
    executor.runAll()
    ran.toList shouldBe List("first", "late")
    late.completed.value shouldBe Some(Success(()))
  }

  it("fails the runtime when the executor rejects the first drain: the accepted receipt settles as failed and later submissions are rejected") {
    val executor = new RejectingExecutor(rejects = Set(1))
    val runtime = Runtime.scheduled(executor)
    val reported = mutable.Buffer[Throwable]()
    runtime.registerUnhandledErrorCallback(reported += _)
    val ran = mutable.Buffer[String]()
    val first = runtime.submit(ran += "first")
    first.isAccepted shouldBe true
    val failure = first.completed.value match {
      case Some(Failure(err: RuntimeFailed)) => err
      case other => fail(s"expected RuntimeFailed, got $other")
    }
    failure.runtime shouldBe runtime
    failure.cause.getMessage shouldBe "execute call 1 rejected"
    runtime.state shouldBe RuntimeState.Failed
    reported.toList shouldBe List(failure)
    val second = runtime.submit(ran += "second")
    second.isAccepted shouldBe false
    second.accepted.value.exists { case Failure(_: SubmissionRejected) => true; case _ => false } shouldBe true
    executor.delegate.runAll()
    ran.isEmpty shouldBe true
  }

  it("fails the runtime when the executor rejects a reschedule: every command still queued settles as failed, none is stranded") {
    val executor = new RejectingExecutor(rejects = Set(2))
    val runtime = Runtime.scheduled(executor, capacity = 16, turnsPerDrain = 1)
    val ran = mutable.Buffer[String]()
    val receipts = Seq("a", "b", "c").map(name => runtime.submit(ran += name))
    executor.calls shouldBe 1
    executor.delegate.runAll() // turn `a` completes, the drain yields: execute call 2 is rejected
    ran.toList shouldBe List("a")
    receipts.head.completed.value shouldBe Some(Success(()))
    receipts.tail.foreach { receipt =>
      receipt.isAccepted shouldBe true
      receipt.completed.value.exists { case Failure(_: RuntimeFailed) => true; case _ => false } shouldBe true
    }
    runtime.state shouldBe RuntimeState.Failed
    runtime.isEntered shouldBe false
  }

  it("yields the executor after the configured number of complete turns and never splits a turn across drains") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor, capacity = 16, turnsPerDrain = 2)
    val ran = mutable.Buffer[String]()
    val v = mutable.Buffer[Var[Int]]()
    runtime.submit(v += Var(0))
    executor.runAll()
    val receipts = (1 to 5).map { n =>
      runtime.submit {
        v.head.signal.foreach(value => if (value == n * 10) v.head.set(value + 1))(new com.raquo.airstream.ownership.ManualOwner)
        v.head.set(n * 10) // the observer's nested write is a child transaction of this turn
        ran += s"turn $n saw ${v.head.now()}"
      }
    }
    executor.executions shouldBe 2 // the first drain, then the one for the five commands
    executor.runNext() shouldBe true // (the first drain already ran) one drain: two complete turns, then a yield
    ran.toList shouldBe List("turn 1 saw 11", "turn 2 saw 21")
    receipts.take(2).foreach(_.completed.value shouldBe Some(Success(())))
    receipts.drop(2).foreach(_.completed.isCompleted shouldBe false)
    executor.pending shouldBe 1
    executor.runAll()
    ran.size shouldBe 5
    receipts.foreach(_.completed.value shouldBe Some(Success(())))
    executor.executions shouldBe 4 // the first drain, then three for the five commands: two yields and the drain that saw the mailbox empty
  }
}
