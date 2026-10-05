package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.ForeignThreadEntry

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

/** The thread half of the entry protocol on JVM and Native: a confined runtime belongs to the thread that created it, and
  * the default runtime belongs to whichever thread holds its permit. A foreign thread is rejected before any graph access
  * and never blocks.
  */
class RuntimeEntrySpec extends UnitSpec {

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

  it("rejects a run on a confined runtime from a thread other than the one that created it") {
    val runtime = Runtime.confined()
    val rejection = onThread("foreign") {
      intercept[ForeignThreadEntry] {
        runtime.run(())
      }
    }
    rejection.runtime shouldBe runtime
    runtime.isEntered shouldBe false
    runtime.run(1) shouldBe 1
  }

  it("rejects an entry into the default runtime while another thread holds it, and admits one once it is released") {
    val inside = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val holder = new Thread(() => Runtime.default.run {
      inside.countDown()
      release.await(5, TimeUnit.SECONDS)
      ()
    }, "holder")
    holder.start()
    inside.await(5, TimeUnit.SECONDS) shouldBe true
    val rejection = intercept[ForeignThreadEntry] {
      Runtime.default.run(())
    }
    rejection.runtime shouldBe Runtime.default
    release.countDown()
    holder.join(5000)
    Runtime.default.isEntered shouldBe false
    Runtime.default.run(2) shouldBe 2
  }
}
