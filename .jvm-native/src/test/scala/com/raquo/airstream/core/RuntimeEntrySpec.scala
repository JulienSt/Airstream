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
    val thread = new Thread(
      () =>
        result.set(try Right(body)
        catch { case err: Throwable => Left(err) }),
      name
    )
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

  it("keeps foreign threads hammering run out of a confined and a dispatched runtime while the bound thread enters") {
    // A bound permit has one writer, its thread: the foreign threads here only ever read it and are refused by the
    // binding before any graph access, however their attempts interleave with the bound thread's entries and releases.
    val foreignThreads = 4
    val attemptsPerThread = 20000
    val ownEntries = 200000
    Seq(Runtime.confined(), Runtime.dispatched(scala.concurrent.ExecutionContext.parasitic)).foreach { runtime =>
      val owner = Thread.currentThread()
      val foreignBodies = new java.util.concurrent.atomic.AtomicInteger(0)
      val rejections = new java.util.concurrent.atomic.AtomicInteger(0)
      val start = new CountDownLatch(1)
      val hammers = (1 to foreignThreads).map { index =>
        val thread = new Thread(
          () => {
            start.await(5, TimeUnit.SECONDS)
            var attempt = 0
            while (attempt < attemptsPerThread) {
              try {
                runtime.run(foreignBodies.incrementAndGet())
              } catch {
                case _: AirstreamError => rejections.incrementAndGet()
              }
              attempt += 1
            }
          },
          s"hammer-$index"
        )
        thread.start()
        thread
      }
      start.countDown()
      var entered = 0
      var wrongHolder = 0
      while (entered < ownEntries) {
        runtime.run {
          if (!runtime.holderName.contains(owner.getName)) wrongHolder += 1
          entered += 1
        }
      }
      hammers.foreach(_.join(30000))
      withClue(s"${runtime.displayName}: ") {
        foreignBodies.get() shouldBe 0
        rejections.get() shouldBe foreignThreads * attemptsPerThread
        wrongHolder shouldBe 0
        runtime.isEntered shouldBe false
      }
    }
  }

  it("rejects an entry into the default runtime while another thread holds it, and admits one once it is released") {
    val inside = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val holder = new Thread(
      () =>
        Runtime.default.run {
          inside.countDown()
          release.await(5, TimeUnit.SECONDS)
          ()
        },
      "holder"
    )
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
