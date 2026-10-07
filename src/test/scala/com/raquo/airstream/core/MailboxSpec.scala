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

  it("reserves one shared input slot across bulk producers without changing FIFO delivery") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor, capacity = 3, turnsPerDrain = 8)
    val ran = mutable.Buffer[String]()
    val first = runtime.submitKeeping(1)(ran += "pty-a")
    val second = runtime.submitKeeping(1)(ran += "pty-b")
    val refused = runtime.submitKeeping(1)(ran += "pty-c")
    first.isAccepted shouldBe true
    second.isAccepted shouldBe true
    refused.isAccepted shouldBe false
    val input = runtime.submit(ran += "input")
    input.isAccepted shouldBe true
    executor.runAll()
    ran.toList shouldBe List("pty-a", "pty-b", "input")
    Seq(first, second, input).foreach(_.completed.value shouldBe Some(Success(())))
    refused.completed.value.exists(_.isFailure) shouldBe true
  }

  it("admits a held-back bulk retry only after draining below the shared reservation threshold") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor, capacity = 4, turnsPerDrain = 1)
    val ran = mutable.Buffer[String]()
    runtime.submitKeeping(2)(ran += "pty-a").isAccepted shouldBe true
    runtime.submitKeeping(2)(ran += "pty-b").isAccepted shouldBe true
    runtime.submit(ran += "input-a").isAccepted shouldBe true
    runtime.submit(ran += "input-b").isAccepted shouldBe true
    val refused = runtime.submitKeeping(2)(ran += "never")
    refused.isAccepted shouldBe false
    executor.runNext() shouldBe true
    runtime.submitKeeping(2)(ran += "never").isAccepted shouldBe false
    executor.runNext() shouldBe true
    runtime.submitKeeping(2)(ran += "never").isAccepted shouldBe false
    executor.runNext() shouldBe true
    val retry = runtime.submitKeeping(2)(ran += "pty-c")
    retry.isAccepted shouldBe true
    (retry.id > refused.id) shouldBe true
    executor.runAll()
    ran.toList shouldBe List("pty-a", "pty-b", "input-a", "input-b", "pty-c")
    retry.completed.value shouldBe Some(Success(()))
  }

  it("rejects invalid reservations before any command effect and permits zero headroom at capacity one") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor, capacity = 1, turnsPerDrain = 1)
    val ran = mutable.Buffer[Int]()
    Seq(-1, 1, Int.MaxValue).foreach { reserve =>
      assertThrows[IllegalArgumentException](runtime.submitKeeping(reserve)(ran += reserve))
    }
    executor.pending shouldBe 0
    runtime.submitKeeping(0)(ran += 0).isAccepted shouldBe true
    runtime.submitKeeping(0)(ran += 1).isAccepted shouldBe false
    executor.runAll()
    ran.toList shouldBe List(0)
  }

  it("closes bulk admission while completing the commands accepted before the close") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor, capacity = 2, turnsPerDrain = 1)
    val ran = mutable.Buffer[Int]()
    val first = runtime.submitKeeping(1)(ran += 1)
    val closing = runtime.close()
    runtime.submitKeeping(1)(ran += 2).isAccepted shouldBe false
    executor.runAll()
    first.completed.value shouldBe Some(Success(()))
    closing.completed.value shouldBe Some(Success(()))
    runtime.state shouldBe RuntimeState.Closed
    runtime.submitKeeping(1)(ran += 3).isAccepted shouldBe false
    ran.toList shouldBe List(1)
  }

  it("runs a direct submission in its owning runtime without imposing a queue reservation") {
    val runtime = Runtime.confined()
    val seen = mutable.Buffer[Runtime]()
    val receipt = runtime.submitKeeping(3)(seen += Runtime.current)
    receipt.isAccepted shouldBe true
    receipt.completed.value shouldBe Some(Success(()))
    seen.toList shouldBe List(runtime)
    assertThrows[IllegalArgumentException](runtime.submitKeeping(-1)(seen += Runtime.current))
    runtime.close()
    runtime.submitKeeping(3)(seen += Runtime.current).isAccepted shouldBe false
    seen.toList shouldBe List(runtime)
  }

  /** An executor that rejects the given `execute` calls, counted from one, and queues the others for the test to run. */
  final private class RejectingExecutor(rejects: Set[Int]) extends ExecutionContext {
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
