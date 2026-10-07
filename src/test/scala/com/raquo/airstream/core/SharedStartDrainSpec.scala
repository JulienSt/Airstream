package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec

import scala.collection.mutable

/** Characterize shared-start drains before changing their storage traversal. */
class SharedStartDrainSpec extends UnitSpec {

  it("reuses a runtime across large, small, and empty shared batches without replaying work") {
    val runtime = Runtime.confined()
    var created = 0
    runtime.traceTo(new RuntimeTrace {
      override def transactionCreated(runtime: Runtime, commandId: Option[Long]): Unit = created += 1
    })
    try runtime.run {
        for (size <- List(64, 1, 0, 3, 0, 128, 1, 0)) {
          val effects = mutable.Buffer.empty[Int]
          val before = created
          Transaction.onStart.shared {
            for (index <- 0 until size) {
              Transaction.onStart.add { _ =>
                effects += index
                Transaction.onStart.shared(())
              }
            }
          }
          effects.toList shouldBe (0 until size).toList
          (created - before) shouldBe (if (size == 0) 0 else size)
          effects.clear()
          Transaction.onStart.shared {
            for (index <- 0 until size) {
              Transaction(_ => effects += index)
            }
          }
          effects.toList shouldBe (0 until size).toList
          Transaction.isClearState shouldBe true
        }
      }
    finally runtime.close()
  }

  it("drains callbacks appended by nested shared before the queued post-start transactions") {
    val runtime = Runtime.confined()
    try runtime.run {
        val effects = mutable.Buffer.empty[String]
        val callbacks = mutable.Buffer.empty[Transaction]
        Transaction.onStart.shared {
          Transaction.onStart.add { transaction =>
            effects += "first"
            callbacks += transaction
            Transaction.onStart.shared {
              Transaction.onStart.add { appended =>
                effects += "appended"
                callbacks += appended
              }
              Transaction(_ => effects += "nested child")
            }
          }
          Transaction.onStart.add { transaction =>
            effects += "second"
            callbacks += transaction
          }
          Transaction(_ => effects += "outer child")
        }
        effects.toList shouldBe List("first", "second", "appended", "outer child", "nested child")
        callbacks.forall(_ eq callbacks.head) shouldBe true
        Transaction.isClearState shouldBe true
      }
    finally runtime.close()
  }

  it("does not create another transaction for an empty shared inside the last callback") {
    val runtime = Runtime.confined()
    var created = 0
    runtime.traceTo(new RuntimeTrace {
      override def transactionCreated(runtime: Runtime, commandId: Option[Long]): Unit = created += 1
    })
    try runtime.run {
        Transaction.onStart.shared {
          Transaction.onStart.add { _ => Transaction.onStart.shared(()) }
        }
        created shouldBe 1
        Transaction.isClearState shouldBe true
      }
    finally runtime.close()
  }

  it("preserves creation order when a shared block contains only post-start transactions") {
    val runtime = Runtime.confined()
    try runtime.run {
        val effects = mutable.Buffer.empty[Int]
        Transaction.onStart.shared {
          Transaction(_ => effects += 1)
          Transaction.onStart.shared {
            Transaction(_ => effects += 2)
            Transaction(_ => effects += 3)
          }
          effects.toList shouldBe Nil
        }
        effects.toList shouldBe List(1, 2, 3)
        Transaction.onStart.shared { Transaction(_ => effects += 4) }
        effects.toList shouldBe List(1, 2, 3, 4)
        Transaction.isClearState shouldBe true
      }
    finally runtime.close()
  }

  it("resumes remaining callbacks and post-start transactions after an unsafe rethrow") {
    val runtime = Runtime.confined()
    try runtime.run {
        runtime.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
        runtime.registerUnhandledErrorCallback(AirstreamError.unsafeRethrowErrorCallback)
        val effects = mutable.Buffer.empty[String]
        val failure = new Exception("shared callback")
        val thrown = intercept[Exception] {
          Transaction.onStart.shared {
            Transaction.onStart.add { _ =>
              effects += "failed"
              throw failure
            }
            Transaction.onStart.add(_ => effects += "remaining")
            Transaction(_ => effects += "child")
          }
        }
        (thrown eq failure) shouldBe true
        effects.toList shouldBe List("failed")
        Transaction.isClearState shouldBe true
        Transaction.onStart.shared { Transaction.onStart.add(_ => effects += "next") }
        effects.toList shouldBe List("failed", "remaining", "next", "child")
        Transaction.isClearState shouldBe true
      }
    finally runtime.close()
  }
}
