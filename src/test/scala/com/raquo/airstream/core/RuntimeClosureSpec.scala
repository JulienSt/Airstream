package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.fixtures.ManualExecutor

import scala.collection.mutable
import scala.util.Success

/** Graceful closure on one thread, with the executor pumped by hand: admission ends
  * atomically, accepted commands still run and settle, cleanup runs once at a serialized point, late deliveries are
  * suppressed, and the runtime releases what it owns and nothing else.
  */
class RuntimeClosureSpec extends UnitSpec {

  it("closes a scheduled runtime gracefully: an accepted command still runs, a later submission is rejected, and the close completes after it") {
    val executor = new ManualExecutor
    val runtime = Runtime.scheduled(executor)
    val ran = mutable.Buffer[Int]()
    val accepted = runtime.submit(ran += 1)
    val closing = runtime.close()
    runtime.state shouldBe RuntimeState.Closing
    val late = runtime.submit(ran += 2)
    late.isAccepted shouldBe false
    closing.completed.isCompleted shouldBe false
    executor.runAll()
    ran.toList shouldBe List(1)
    accepted.completed.value shouldBe Some(Success(()))
    closing.completed.value shouldBe Some(Success(()))
    runtime.state shouldBe RuntimeState.Closed
  }
}
