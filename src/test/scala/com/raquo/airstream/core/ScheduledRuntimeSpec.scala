package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.{CrossRuntimeAccess, ScheduledRuntimeEntry, SubmissionRejected}
import com.raquo.airstream.fixtures.ManualExecutor
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import scala.collection.mutable
import scala.util.{Failure, Success}

/** The scheduled strategy on one thread, with the executor pumped by hand: a submitted
  * command is admitted into the mailbox and runs later as a turn under the runtime's permit when the executor runs the
  * drain; its receipt completes after the command's synchronous descendant work has drained.
  */
class ScheduledRuntimeSpec extends UnitSpec {

  it("runs a submitted command as a turn when the executor runs the drain, and completes the receipt after the command's synchronous descendants") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor)
    val seen = mutable.Buffer[String]()
    val receipt = runtime.submit {
      Runtime.current shouldBe runtime
      val owner = new ManualOwner
      val v = Var(0)
      v.signal.foreach { value =>
        seen += s"value $value"
        if (value == 1) {
          v.set(2)
        }
      }(owner)
      v.set(1)
      seen += "command returned"
    }
    receipt.accepted.isCompleted shouldBe true
    receipt.completed.isCompleted shouldBe false
    executor.executions shouldBe 1
    seen.isEmpty shouldBe true
    executor.runAll()
    receipt.completed.value shouldBe Some(Success(()))
    seen.toList shouldBe List("value 0", "value 1", "value 2", "command returned")
    runtime.isEntered shouldBe false
    Runtime.current shouldBe Runtime.default
  }

  it("starts accepted commands in admission order, FIFO per runtime, whether they were submitted before or during a drain") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor)
    val ran = mutable.Buffer[Int]()
    val receipts = (1 to 3).map(n => runtime.submit(ran += n))
    receipts.map(_.id) shouldBe Seq(1L, 2L, 3L)
    val fourth = runtime.submit {
      // Submitted from inside turn 1: queued behind the commands admitted before it, never run inline.
      ran += 4
    }
    runtime.submit {
      runtime.submit(ran += 6)
      ran += 5
    }
    executor.runAll()
    ran.toList shouldBe List(1, 2, 3, 4, 5, 6)
    (receipts :+ fourth).foreach(_.completed.value shouldBe Some(Success(())))
  }

  it("rejects a submission beyond the mailbox's capacity before any effect, and admits a retry under a new id once there is room") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor, capacity = 2, turnsPerDrain = 32)
    val ran = mutable.Buffer[String]()
    val first = runtime.submit(ran += "first")
    val second = runtime.submit(ran += "second")
    val overflow = runtime.submit(ran += "overflow")
    first.isAccepted shouldBe true
    second.isAccepted shouldBe true
    overflow.isAccepted shouldBe false
    val rejection = overflow.accepted.value match {
      case Some(Failure(err: SubmissionRejected)) => err
      case other => fail(s"expected SubmissionRejected, got $other")
    }
    rejection.runtime shouldBe runtime
    overflow.completed.value shouldBe Some(Failure(rejection))
    runtime.state shouldBe RuntimeState.Open
    executor.runAll()
    ran.toList shouldBe List("first", "second")
    val retry = runtime.submit(ran += "retry")
    retry.isAccepted shouldBe true
    (retry.id == overflow.id) shouldBe false
    executor.runAll()
    ran.toList shouldBe List("first", "second", "retry")
    retry.completed.value shouldBe Some(Success(()))
  }

  it("settles the receipt of a command that throws with its exception and keeps the runtime open for the next command") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor)
    val boom = new IllegalStateException("boom")
    val failing = runtime.submit(throw boom)
    val next = runtime.submit(())
    executor.runAll()
    failing.completed.value shouldBe Some(Failure(boom))
    next.completed.value shouldBe Some(Success(()))
    runtime.state shouldBe RuntimeState.Open
    runtime.isEntered shouldBe false
    Runtime.current shouldBe Runtime.default
  }

  it("rejects a synchronous run, read and construction on a scheduled runtime outside its turns, and nests a reentrant run inside one") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor)
    intercept[ScheduledRuntimeEntry](runtime.run(())).runtime shouldBe runtime
    val published = mutable.Buffer[Var[Int]]()
    val receipt = runtime.submit {
      published += Var(1)
      runtime.run(Runtime.current shouldBe runtime)
    }
    executor.runAll()
    receipt.completed.value shouldBe Some(Success(()))
    val v = published.head
    intercept[ScheduledRuntimeEntry](v.now())
    intercept[ScheduledRuntimeEntry](v.signal.map(_ + 1))
    intercept[ScheduledRuntimeEntry](v.set(2))
    val read = runtime.submit(v.now() shouldBe 1)
    executor.runAll()
    read.completed.value shouldBe Some(Success(()))
  }

  it("rejects a synchronous entry into a scheduled runtime from inside another runtime's turn, before touching its permit") {
    val executor = new ManualExecutor
    val a = Runtime.scheduled(executor)
    val b = Runtime.scheduled(executor)
    val outcome = mutable.Buffer[Throwable]()
    a.submit {
      outcome += intercept[CrossRuntimeAccess](b.run(()))
      b.isEntered shouldBe false
    }
    executor.runAll()
    outcome.toList.collect { case CrossRuntimeAccess(active, target) => (active, target) } shouldBe List((a, b))
  }

  it("runs a command submitted to a direct runtime at once as a turn, and inline when submitted from inside its own turn") {
    val runtime = Runtime.confined()
    val ran = mutable.Buffer[String]()
    val outer = runtime.submit {
      ran += "outer"
      val inner = runtime.submit(ran += "inner")
      inner.completed.value shouldBe Some(Success(()))
    }
    outer.isAccepted shouldBe true
    outer.completed.value shouldBe Some(Success(()))
    ran.toList shouldBe List("outer", "inner")
    runtime.isEntered shouldBe false
    val rejected = Runtime.confined().run(runtime.submit(ran += "cross"))
    rejected.isAccepted shouldBe false
    rejected.completed.value.exists(_.isFailure) shouldBe true
    ran.toList shouldBe List("outer", "inner")
  }

  it("reports a turn to the trace in order: permit, turn start, transactions tagged with the command, release, context, completion, then the receipt") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor)
    val events = mutable.Buffer[String]()
    runtime.traceTo(new RuntimeTrace {
      override def permitAcquired(r: Runtime): Unit = events += "permit-acquired"
      override def permitReleased(r: Runtime): Unit = events += "permit-released"
      override def contextRestored(r: Runtime): Unit = events += "context-restored"
      override def turnStarted(r: Runtime, id: Long): Unit = events += s"turn-started $id"
      override def turnCompleted(r: Runtime, id: Long): Unit = events += s"turn-completed $id"
      override def transactionCreated(r: Runtime, id: Option[Long]): Unit = events += s"transaction ${id.fold("root")(_.toString)}"
    })
    val issued = mutable.Buffer[Receipt]()
    val receipt = runtime.submit {
      val v = Var(0)
      v.set(1)
      issued.foreach(_.completed.isCompleted shouldBe false)
    }
    issued += receipt
    executor.runAll()
    events.toList shouldBe List("permit-acquired", "turn-started 1", "transaction 1", "permit-released", "context-restored", "turn-completed 1")
    receipt.completed.value shouldBe Some(Success(()))
    val direct = Runtime.confined()
    direct.traceTo(new RuntimeTrace {
      override def transactionCreated(r: Runtime, id: Option[Long]): Unit = events += s"direct ${id.fold("root")(_.toString)}"
    })
    direct.run(Var(0).set(1))
    events.last shouldBe "direct root"
  }
}
