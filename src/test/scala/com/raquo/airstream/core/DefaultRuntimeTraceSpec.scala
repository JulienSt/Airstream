package com.raquo.airstream.core

import com.raquo.airstream.state.Var
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class DefaultRuntimeTraceSpec extends AnyFunSuite with Matchers {

  test("ordinary default-runtime transactions have no command id before the first submission") {
    val runtime = Runtime.default
    val commands = mutable.Buffer[Option[Long]]()
    runtime.traceTo(new RuntimeTrace {
      override def transactionCreated(runtime: Runtime, commandId: Option[Long]): Unit = commands += commandId
    })
    try {
      val value = Var(0)
      value.set(1)
      // Assert outside the transaction, where the unhandled-error router cannot swallow a failed expectation.
      commands.toList shouldBe List(None)
    } finally {
      runtime.traceTo(null)
    }
  }
}
