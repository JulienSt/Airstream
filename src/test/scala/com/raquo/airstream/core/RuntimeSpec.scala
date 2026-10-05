package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.CrossRuntimeAccess

/** The entry protocol of a runtime on one thread: `run` installs the runtime as the construction context, reentrant runs
  * nest, every exit restores the previous context, and a synchronous entry into another runtime from inside a turn is
  * rejected before that runtime is touched.
  */
class RuntimeSpec extends UnitSpec {

  it("run returns the body's value and installs the runtime as the construction context") {
    val runtime = Runtime.confined()
    Runtime.current shouldBe Runtime.default
    val result = runtime.run {
      Runtime.current shouldBe runtime
      42
    }
    result shouldBe 42
    Runtime.current shouldBe Runtime.default
  }

  it("restores the previous context when the body throws") {
    val runtime = Runtime.confined()
    val err = new RuntimeException("boom")
    val thrown = intercept[RuntimeException] {
      runtime.run {
        throw err
      }
    }
    thrown shouldBe err
    Runtime.current shouldBe Runtime.default
    runtime.run(1) shouldBe 1
  }

  it("nests reentrant runs of the same runtime") {
    val runtime = Runtime.confined()
    val result = runtime.run {
      runtime.run {
        Runtime.current shouldBe runtime
        "inner"
      }
    }
    result shouldBe "inner"
    Runtime.current shouldBe Runtime.default
    runtime.isEntered shouldBe false
  }

  it("rejects a synchronous entry into another runtime from inside a turn, before that runtime is entered") {
    val a = Runtime.confined()
    val b = Runtime.confined()
    a.run {
      val rejection = intercept[CrossRuntimeAccess] {
        b.run(())
      }
      rejection.active shouldBe a
      rejection.target shouldBe b
      b.isEntered shouldBe false
      Runtime.current shouldBe a
    }
    b.run(1) shouldBe 1
  }

  it("names the default runtime and keeps it distinct from confined runtimes") {
    Runtime.default.toString shouldBe "Runtime.default"
    val runtime = Runtime.confined()
    (runtime eq Runtime.default) shouldBe false
    runtime.setDisplayName("worker").toString shouldBe "worker"
  }
}
