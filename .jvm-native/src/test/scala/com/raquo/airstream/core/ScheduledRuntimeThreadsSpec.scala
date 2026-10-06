package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.{CrossRuntimeAccess, RuntimeFailed, ScheduledRuntimeEntry, SubmissionRejected, WaitInsideTurn}
import com.raquo.airstream.fixtures.ThreadPool
import com.raquo.airstream.ownership.{ManualOwner, Subscription}
import com.raquo.airstream.state.Var

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/** The scheduled strategy on real threads (JVM and Native): one drainer under a racing pool, no lost command
  * under two producers, context and permit restored after every exit path with the worker reused by another
  * runtime, cross-runtime lock cycles rejected before blocking, parallel progress of separate runtimes
  *, waits rejected inside a turn, direct submit from a foreign thread, and the dispatched variant for a UI
  * thread. Every wait is a latch with a timeout, never a sleep.
  */
class ScheduledRuntimeThreadsSpec extends UnitSpec {

  private val timeout = 5.seconds

  private def awaitLatch(latch: CountDownLatch): Boolean = latch.await(timeout.toMillis, TimeUnit.MILLISECONDS)

  private def withPool[A](name: String, threads: Int)(body: ThreadPool => A): A = {
    val pool = new ThreadPool(name, threads)
    try body(pool)
    finally pool.close()
  }

  it("lets at most one drainer into a runtime when two producers race first submissions into it on a two-thread pool") {
    withPool("tw02", 2) { pool =>
      val runtime = Runtime.scheduled(pool)
      val inside = new AtomicInteger(0)
      val maximum = new AtomicInteger(0)
      val firstInside = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val submitted = new CountDownLatch(2)
      val receipts = new AtomicReference(Vector.empty[Receipt])
      val producers = (1 to 2).map { producer =>
        new Thread(() => {
          val receipt = runtime.submit {
            val now = inside.incrementAndGet()
            maximum.updateAndGet(current => math.max(current, now))
            if (producer == 1) {
              firstInside.countDown()
              awaitLatch(release)
            }
            inside.decrementAndGet()
            ()
          }
          receipts.updateAndGet(_ :+ receipt)
          submitted.countDown()
        }, s"producer-$producer")
      }
      producers.foreach(_.start())
      awaitLatch(submitted) shouldBe true
      awaitLatch(firstInside) shouldBe true
      release.countDown()
      receipts.get.foreach(_.awaitCompleted(timeout))
      maximum.get shouldBe 1
      runtime.isEntered shouldBe false
    }
  }

  it("completes every command two producers submit while drains wind down: none is lost to a missed wake-up") {
    withPool("tw03", 2) { pool =>
      val runtime = Runtime.scheduled(pool, capacity = 1000, turnsPerDrain = 3)
      val completed = new AtomicInteger(0)
      val receipts = new java.util.concurrent.ConcurrentLinkedQueue[Receipt]()
      val producers = (1 to 2).map { producer =>
        new Thread(() => (1 to 200).foreach(_ => receipts.add(runtime.submit { completed.incrementAndGet(); () })), s"producer-$producer")
      }
      producers.foreach(_.start())
      producers.foreach(_.join(timeout.toMillis))
      receipts.size shouldBe 400
      receipts.forEach(receipt => receipt.awaitCompleted(timeout))
      completed.get shouldBe 400
      runtime.state shouldBe RuntimeState.Open
    }
  }

  it("restores context and permit after a command, an observer, a projection or a cleanup throws, and the same worker then serves another runtime") {
    withPool("tw12", 1) { pool =>
      val a = Runtime.scheduled(pool)
      val b = Runtime.scheduled(pool)
      val swallow: Throwable => Unit = _ => ()
      a.registerUnhandledErrorCallback(swallow)
      a.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
      val boom = new IllegalStateException("fault")
      val faults: Seq[() => Unit] = Seq(
        () => throw boom,
        () => { val v = Var(0); v.signal.foreach(_ => throw boom)(new ManualOwner); v.set(1) },
        () => { val v = Var(0); v.signal.map[Int](_ => throw boom).foreach(_ => ())(new ManualOwner); v.set(1) },
        () => { val owner = new ManualOwner; new Subscription(owner, () => throw boom); owner.killSubscriptions() }
      )
      faults.foreach { fault =>
        val receipt = a.submit(fault())
        try receipt.awaitCompleted(timeout)
        catch { case _: IllegalStateException => () }
        receipt.completed.isCompleted shouldBe true
        a.holderName shouldBe None
        val seenContext = new AtomicReference[Runtime](null)
        val seenThread = new AtomicReference[String](null)
        b.submit {
          seenContext.set(Runtime.current)
          seenThread.set(Thread.currentThread().getName)
        }.awaitCompleted(timeout)
        seenContext.get shouldBe b
        pool.threadNames.contains(seenThread.get) shouldBe true
      }
      a.state shouldBe RuntimeState.Open
    }
  }

  it("rejects a synchronous call into runtime B from inside runtime A's turn while B calls A, before either blocks on the other") {
    withPool("tw14-a", 1) { poolA =>
      withPool("tw14-b", 1) { poolB =>
        val a = Runtime.scheduled(poolA)
        val b = Runtime.scheduled(poolB)
        val bothInside = new CountDownLatch(2)
        val results = new java.util.concurrent.ConcurrentLinkedQueue[(String, Throwable)]()
        def crossCall(name: String, other: Runtime): Unit = {
          bothInside.countDown()
          awaitLatch(bothInside)
          val outcome =
            try { other.run(()); null }
            catch { case err: Throwable => err }
          results.add((name, outcome))
          ()
        }
        val fromA = a.submit(crossCall("A", b))
        val fromB = b.submit(crossCall("B", a))
        fromA.awaitCompleted(timeout)
        fromB.awaitCompleted(timeout)
        val byName = results.toArray.toList.collect { case (name: String, err: Throwable) => name -> err }.toMap
        byName.keySet shouldBe Set("A", "B")
        byName("A") match {
          case CrossRuntimeAccess(active, target) => (active, target) shouldBe ((a, b))
          case other => fail(s"A: expected CrossRuntimeAccess, got $other")
        }
        byName("B") match {
          case CrossRuntimeAccess(active, target) => (active, target) shouldBe ((b, a))
          case other => fail(s"B: expected CrossRuntimeAccess, got $other")
        }
        a.isEntered shouldBe false
        b.isEntered shouldBe false
      }
    }
  }

  it("lets runtime B complete a command while runtime A is held at a barrier on the same pool: separate runtimes progress in parallel") {
    withPool("tw18", 2) { pool =>
      val a = Runtime.scheduled(pool)
      val b = Runtime.scheduled(pool)
      val aInside = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val fromA = a.submit {
        aInside.countDown()
        awaitLatch(release)
        ()
      }
      awaitLatch(aInside) shouldBe true
      val fromB = b.submit(())
      fromB.awaitCompleted(timeout)
      fromB.completed.value shouldBe Some(Success(()))
      fromA.completed.isCompleted shouldBe false
      release.countDown()
      fromA.awaitCompleted(timeout)
    }
  }

  it("rejects a blocking wait for a receipt from inside a turn before it blocks, and serves it from outside every runtime") {
    withPool("wait", 1) { pool =>
      val runtime = Runtime.scheduled(pool)
      val other = runtime.submit(())
      other.awaitCompleted(timeout)
      val rejection = new AtomicReference[Throwable](null)
      runtime.submit {
        val pending = runtime.submit(())
        rejection.set(intercept[WaitInsideTurn](pending.awaitCompleted(timeout)))
      }.awaitCompleted(timeout)
      rejection.get match {
        case WaitInsideTurn(active, _) => active shouldBe runtime
        case other => fail(s"expected WaitInsideTurn, got $other")
      }
      val direct = Runtime.confined()
      intercept[WaitInsideTurn](direct.run(other.awaitCompleted(timeout))).active shouldBe direct
    }
  }

  it("rejects a submission to a confined runtime from a foreign thread with a settled rejected receipt and no effect") {
    val runtime = Runtime.confined()
    val v = runtime.run(Var(0))
    val receipt = new AtomicReference[Receipt](null)
    val thread = new Thread(() => receipt.set(runtime.submit(v.set(1))), "foreign")
    thread.start()
    thread.join(timeout.toMillis)
    receipt.get.isAccepted shouldBe false
    receipt.get.completed.value.exists { case Failure(_: SubmissionRejected) => true; case _ => false } shouldBe true
    v.now() shouldBe 0
  }

  it("runs a dispatched runtime's submitted commands on its dispatcher thread, which may also run synchronously; other threads are told to submit") {
    withPool("dispatcher", 1) { dispatcher =>
      val created = new CountDownLatch(1)
      val holder = new AtomicReference[Runtime](null)
      dispatcher.execute(() => {
        holder.set(Runtime.dispatched(dispatcher))
        created.countDown()
      })
      awaitLatch(created) shouldBe true
      val runtime = holder.get
      intercept[ScheduledRuntimeEntry](runtime.run(()))
      val turnThread = new AtomicReference[String](null)
      val v = new AtomicReference[Var[Int]](null)
      runtime.submit {
        turnThread.set(Thread.currentThread().getName)
        v.set(Var(0))
      }.awaitCompleted(timeout)
      dispatcher.threadNames.contains(turnThread.get) shouldBe true
      val direct = new CountDownLatch(1)
      val directOutcome = new AtomicReference[Either[Throwable, Int]](null)
      dispatcher.execute(() => {
        directOutcome.set(try Right(runtime.run { v.get.set(5); v.get.now() })
        catch { case err: Throwable => Left(err) })
        direct.countDown()
      })
      awaitLatch(direct) shouldBe true
      directOutcome.get shouldBe Right(5)
      runtime.state shouldBe RuntimeState.Open
    }
  }

  it("fails a dispatched runtime whose dispatcher ran a drain on another thread, instead of touching the graph from it") {
    withPool("elsewhere", 1) { pool =>
      val runtime = Runtime.dispatched(pool) // bound to the test thread, but the pool runs drains on its own thread
      runtime.unregisterUnhandledErrorCallback(AirstreamError.consoleErrorCallback)
      val receipt = runtime.submit(())
      val failure = intercept[RuntimeFailed](receipt.awaitCompleted(timeout))
      failure.runtime shouldBe runtime
      runtime.state shouldBe RuntimeState.Failed
      runtime.isEntered shouldBe false
    }
  }
}
