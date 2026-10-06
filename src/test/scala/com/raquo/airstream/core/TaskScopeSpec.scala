package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Success, Try}

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
}
