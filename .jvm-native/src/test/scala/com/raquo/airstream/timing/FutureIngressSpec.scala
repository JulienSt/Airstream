package com.raquo.airstream.timing

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.{EventStream, Observer, Receipt, Runtime, Signal}
import com.raquo.airstream.fixtures.ThreadPool
import com.raquo.airstream.ownership.ManualOwner

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

/** Asynchronous ingress of the Future adapters on real threads: a completion callback that runs on
  * a worker does not touch the adapter or the graph there; it hands the completion to the owning runtime, which delivers it
  * in a turn of its own. Every wait is a latch with a timeout, never a sleep.
  */
class FutureIngressSpec extends UnitSpec {

  private val timeout = 5.seconds

  private def awaitLatch(latch: CountDownLatch): Boolean = latch.await(timeout.toMillis, TimeUnit.MILLISECONDS)

  private def withPools[A](body: (ThreadPool, ThreadPool) => A): A = {
    val turns = new ThreadPool("turns", 1)
    val callbacks = new ThreadPool("callbacks", 1)
    try body(turns, callbacks)
    finally {
      turns.close()
      callbacks.close()
    }
  }

  /** Submit `body` and wait for its turn to drain, from outside every runtime. */
  private def inTurn(runtime: Runtime)(body: => Unit): Unit = runtime.submit(body).awaitCompleted(timeout)

  /** Hold a turn of `runtime` until the returned latch is released; returns once the turn is running. */
  private def holdTurn(runtime: Runtime): (CountDownLatch, Receipt) = {
    val inside = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val held = runtime.submit {
      inside.countDown()
      awaitLatch(release)
      ()
    }
    awaitLatch(inside) shouldBe true
    (release, held)
  }

  it("delivers a stream's Future completed on a worker during a held turn in a later turn of the owning runtime") {
    withPools { (turns, callbacks) =>
      val runtime = Runtime.scheduled(turns)
      val promise = Promise[Int]()
      val seen = new AtomicReference(Vector.empty[(Int, String)])
      inTurn(runtime) {
        EventStream
          .fromFuture(promise.future)(callbacks: ExecutionContext)
          .foreach(value => seen.updateAndGet(_ :+ ((value, Thread.currentThread.getName))))(new ManualOwner)
      }
      val (release, held) = holdTurn(runtime)
      val completedOnWorker = new CountDownLatch(1)
      promise.success(1)
      callbacks.execute(() => completedOnWorker.countDown())
      awaitLatch(completedOnWorker) shouldBe true
      seen.get shouldBe Vector.empty
      release.countDown()
      held.awaitCompleted(timeout)
      inTurn(runtime)(())
      seen.get.map(_._1) shouldBe Vector(1)
      turns.threadNames.contains(seen.get.head._2) shouldBe true
      turns.reported shouldBe Nil
      callbacks.reported shouldBe Nil
    }
  }

  it("updates a signal from a Future completed on a worker during a held turn in a later turn of the owning runtime") {
    withPools { (turns, callbacks) =>
      val runtime = Runtime.scheduled(turns)
      val promise = Promise[Int]()
      val seen = new AtomicReference(Vector.empty[(Option[Int], String)])
      inTurn(runtime) {
        Signal
          .fromFuture(promise.future)(callbacks: ExecutionContext)
          .foreach(value => seen.updateAndGet(_ :+ ((value, Thread.currentThread.getName))))(new ManualOwner)
      }
      val (release, held) = holdTurn(runtime)
      val completedOnWorker = new CountDownLatch(1)
      promise.success(2)
      callbacks.execute(() => completedOnWorker.countDown())
      awaitLatch(completedOnWorker) shouldBe true
      seen.get.map(_._1) shouldBe Vector(None)
      release.countDown()
      held.awaitCompleted(timeout)
      inTurn(runtime)(())
      seen.get.map(_._1) shouldBe Vector(None, Some(2))
      turns.threadNames.contains(seen.get.last._2) shouldBe true
      turns.reported shouldBe Nil
      callbacks.reported shouldBe Nil
    }
  }

  it("delivers an already completed Future on an inline execution context when the start finishes, as a direct runtime does") {
    withPools { (turns, _) =>
      def subscribe(seen: AtomicReference[Vector[String]]): Unit = {
        EventStream
          .fromFuture(Future.successful(5))(ExecutionContext.parasitic)
          .foreach(value => seen.updateAndGet(_ :+ s"value $value"))(new ManualOwner)
        seen.updateAndGet(_ :+ "subscribed")
        ()
      }
      val scheduled = Runtime.scheduled(turns)
      val seenScheduled = new AtomicReference(Vector.empty[String])
      inTurn(scheduled)(subscribe(seenScheduled))
      val confined = Runtime.confined()
      val seenConfined = new AtomicReference(Vector.empty[String])
      confined.run(subscribe(seenConfined))
      // The callback runs inline inside the start; its transaction waits for the shared start, which ends inside `foreach`.
      seenConfined.get shouldBe Vector("value 5", "subscribed")
      seenScheduled.get shouldBe seenConfined.get
    }
  }

  it("delivers a failed Future's error to the observer in a turn of the owning runtime") {
    withPools { (turns, callbacks) =>
      val runtime = Runtime.scheduled(turns)
      val boom = new IllegalStateException("future failed")
      val errors = new AtomicReference(Vector.empty[Throwable])
      inTurn(runtime) {
        EventStream
          .fromFuture(Future.failed[Int](boom))(callbacks: ExecutionContext)
          .addObserver(Observer.withRecover[Int](_ => (), { case err => errors.updateAndGet(_ :+ err) }))(new ManualOwner)
      }
      val drained = new CountDownLatch(1)
      callbacks.execute(() => drained.countDown())
      awaitLatch(drained) shouldBe true
      inTurn(runtime)(())
      errors.get shouldBe Vector(boom)
    }
  }
}
