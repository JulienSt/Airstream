package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.CrossRuntimeAccess
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import scala.collection.mutable

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

  it("writes a Var in the runtime it was created in, even when the writer is outside every run") {
    val runtime = Runtime.confined()
    val owner = runtime.run(new ManualOwner)
    val v = runtime.run(Var(0))
    val seen = mutable.Buffer[Runtime]()
    runtime.run(v.signal.foreach(_ => seen += Runtime.current)(owner))
    v.set(1)
    seen.toList shouldBe List(runtime, runtime)
    runtime.isEntered shouldBe false
    Runtime.current shouldBe Runtime.default
  }

  it("gives a derived observable the runtime of its parent, not the runtime of the thread that derived it") {
    val runtime = Runtime.confined()
    val owner = runtime.run(new ManualOwner)
    val v = runtime.run(Var(0))
    val derived = v.signal.map(_ + 1)
    val seen = mutable.Buffer[(Int, Runtime)]()
    derived.foreach(value => seen += ((value, Runtime.current)))(owner)
    v.set(1)
    seen.toList shouldBe List((1, runtime), (2, runtime))
  }

  it("runs a subscription's cleanup inside the owner's runtime when it is killed from outside every run") {
    val runtime = Runtime.confined()
    val owner = runtime.run(new ManualOwner)
    val v = runtime.run(Var(0))
    val subscription = runtime.run(v.signal.foreach(_ => ())(owner))
    val cleanupRuntimes = mutable.Buffer[Runtime]()
    val stream = runtime.run(EventStream.fromCustomSource[Int](start = (_, _, _, _) => (), stop = _ => cleanupRuntimes += Runtime.current))
    val streamSubscription = stream.addObserver(Observer.empty)(owner)
    subscription.kill()
    streamSubscription.kill()
    cleanupRuntimes.toList shouldBe List(runtime)
    runtime.isEntered shouldBe false
  }

  it("allocates update ids per runtime: a fresh runtime's first update is id 1 whatever other runtimes did") {
    implicit val protectedAccess: Protected = Protected.protectedAccessEvidence
    val a = Runtime.confined()
    val b = Runtime.confined()
    val va = a.run(Var(0))
    val vb = b.run(Var(0))
    va.set(1)
    va.set(2)
    vb.set(1)
    Protected.lastUpdateId(va.signal) shouldBe 2
    Protected.lastUpdateId(vb.signal) shouldBe 1
  }
}
