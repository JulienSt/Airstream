package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.fixtures.ManualExecutor
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import scala.collection.mutable
import scala.util.Success

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
}
