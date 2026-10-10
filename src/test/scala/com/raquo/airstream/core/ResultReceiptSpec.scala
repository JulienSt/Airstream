package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.fixtures.ManualExecutor

import scala.util.Success

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
}
