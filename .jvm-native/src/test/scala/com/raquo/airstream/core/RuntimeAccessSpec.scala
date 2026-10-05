package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.{CrossRuntimeAccess, ForeignThreadEntry}
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import java.util.concurrent.atomic.AtomicReference

/** Every supported access path requires valid entry: a read, a write, a subscription and the construction
  * of a derived observable are rejected on a foreign thread before they touch the graph, and the construction of a
  * derived observable from inside another runtime's turn is rejected as a cross-runtime access. The rejections are
  * exceptions at the call site; no thread ever waits for the runtime.
  */
class RuntimeAccessSpec extends UnitSpec {

  private def onThread[A](name: String)(body: => A): A = {
    val result = new AtomicReference[Either[Throwable, A]]()
    val thread = new Thread(() => result.set(try Right(body) catch { case err: Throwable => Left(err) }), name)
    thread.start()
    thread.join(5000)
    result.get() match {
      case null => fail(s"$name did not finish")
      case Left(err) => throw err
      case Right(value) => value
    }
  }

  it("rejects a lazy read of a confined runtime's signal from a foreign thread, before the value is evaluated") {
    val runtime = Runtime.confined()
    var evaluations = 0
    val v = runtime.run(Var(1))
    val derived = runtime.run(v.signal.map { value => evaluations += 1; value + 1 })
    val rejection = onThread("foreign") {
      intercept[ForeignThreadEntry] {
        derived.now()
      }
    }
    rejection.runtime shouldBe runtime
    evaluations shouldBe 0
    derived.now() shouldBe 2
    evaluations shouldBe 1
  }

  it("rejects a write and a subscription from a foreign thread, leaving the graph untouched") {
    val runtime = Runtime.confined()
    val owner = runtime.run(new ManualOwner)
    val v = runtime.run(Var(1))
    onThread("foreign") {
      intercept[ForeignThreadEntry](v.set(2))
      intercept[ForeignThreadEntry](v.signal.foreach(_ => ())(owner))
    }
    v.now() shouldBe 1
    BaseObservable.numAllObservers(v.signal) shouldBe 0
  }

  it("rejects constructing a derived observable of a confined runtime from a foreign thread") {
    val runtime = Runtime.confined()
    val v = runtime.run(Var(1))
    val rejection = onThread("foreign") {
      intercept[ForeignThreadEntry] {
        v.signal.map(_ + 1)
      }
    }
    rejection.runtime shouldBe runtime
  }

  it("rejects constructing a derived observable of another runtime from inside a turn, before it is connected") {
    val a = Runtime.confined()
    val b = Runtime.confined()
    val source = b.run(Var(1))
    val rejection = intercept[CrossRuntimeAccess] {
      a.run(source.signal.map(_ + 1))
    }
    rejection.active shouldBe a
    rejection.target shouldBe b
    a.isEntered shouldBe false
    b.isEntered shouldBe false
  }
}
