package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.fixtures.ManualExecutor

import scala.collection.mutable

/** Fairness per runtime, on every platform with a deterministic budgeted scheduler. The
  * scheduling assumption is: the executor runs the tasks it is handed in FIFO order, and a drain runs
  * at most `turnsPerDrain` complete turns before it hands itself back to the executor. Under that assumption a runnable
  * runtime B waits for at most one drain budget of runtime A's turns, however much work A has or keeps producing. No
  * wall-clock promise is made, and an executor that is not FIFO is outside the claim.
  */
class RuntimeFairnessSpec extends UnitSpec {

  /** Pump `executor` until `done` or `limit` tasks ran; the number of tasks run. */
  private def pumpUntil(executor: ManualExecutor, limit: Int)(done: => Boolean): Int = {
    var steps = 0
    while (!done && steps < limit && executor.runNext()) {
      steps += 1
    }
    steps
  }

  it("runs B within one drain budget of A's turns when B arrives behind a long queue on A") {
    Seq(1, 4, 32).foreach { budget =>
      Seq(10, 100, 500).foreach { queued =>
        val executor = new ManualExecutor
        val a = Runtime.scheduled(executor, capacity = 1000, turnsPerDrain = budget)
        val b = Runtime.scheduled(executor, capacity = 10, turnsPerDrain = budget)
        val order = mutable.Buffer[String]()
        (1 to queued).foreach(n => a.submit(order += s"a$n"))
        b.submit(order += "b")
        executor.runAll()
        withClue(s"budget $budget, $queued queued on A: ") {
          order.size shouldBe queued + 1
          (order.indexOf("b") <= budget) shouldBe true
        }
      }
    }
  }

  it("runs B within one drain budget of A's turns after B arrives in the middle of A's drain") {
    Seq(1, 4, 32).foreach { budget =>
      val executor = new ManualExecutor
      val a = Runtime.scheduled(executor, capacity = 1000, turnsPerDrain = budget)
      val b = Runtime.scheduled(executor, capacity = 10, turnsPerDrain = budget)
      val order = mutable.Buffer[String]()
      val arrival = budget / 2 + 1
      (1 to 200).foreach { n =>
        a.submit {
          order += s"a$n"
          if (n == arrival) {
            b.submit(order += "b")
          }
        }
      }
      executor.runAll()
      withClue(s"budget $budget: ") {
        (order.indexOf("b") - order.indexOf(s"a$arrival") <= budget) shouldBe true
      }
    }
  }

  it("runs B while A keeps producing: every turn of A submits another, and B still runs within one budget") {
    Seq(1, 4, 32).foreach { budget =>
      val executor = new ManualExecutor
      val a = Runtime.scheduled(executor, capacity = 10, turnsPerDrain = budget)
      val b = Runtime.scheduled(executor, capacity = 10, turnsPerDrain = budget)
      val order = mutable.Buffer[String]()
      def endless(n: Int): Unit = {
        order += s"a$n"
        a.submit(endless(n + 1))
        ()
      }
      a.submit(endless(1))
      b.submit(order += "b")
      pumpUntil(executor, limit = 10000)(order.contains("b"))
      withClue(s"budget $budget: ") {
        order.contains("b") shouldBe true
        (order.indexOf("b") <= budget) shouldBe true
      }
      a.state shouldBe RuntimeState.Open
    }
  }
}
