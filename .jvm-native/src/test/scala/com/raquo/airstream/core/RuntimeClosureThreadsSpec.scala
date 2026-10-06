package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.ClosedRuntimeEntry
import com.raquo.airstream.fixtures.ThreadPool
import com.raquo.airstream.state.Var

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Graceful closure on real threads: a close racing producers leaves every receipt terminal
  * and starts no turn after the runtime closed; a shared executor stays usable; a failing cleanup step is reported while
  * the context and the permit are restored; a dispatched runtime's own thread is turned away afterwards.
  */
class RuntimeClosureThreadsSpec extends UnitSpec {

  private val timeout = 5.seconds

  private def awaitLatch(latch: CountDownLatch): Boolean = latch.await(timeout.toMillis, TimeUnit.MILLISECONDS)

  /** Record the turn starts and the close of `runtime`, in the order the runtime reports them. */
  private def traced(runtime: Runtime): ConcurrentLinkedQueue[String] = {
    val events = new ConcurrentLinkedQueue[String]()
    runtime.traceTo(new RuntimeTrace {
      override def turnStarted(runtime: Runtime, commandId: Long): Unit = { events.add(s"turn $commandId"); () }
      override def runtimeClosed(runtime: Runtime): Unit = { events.add("closed"); () }
    })
    events
  }

  it("settles every receipt as completed or rejected when a close races two producers, and starts no turn after the close") {
    val pool = new ThreadPool("closure-turns", 2)
    try {
      val runtime = Runtime.scheduled(pool)
      val events = traced(runtime)
      val ran = new AtomicInteger(0)
      val receipts = new ConcurrentLinkedQueue[Receipt]()
      val closing = new AtomicReference[Receipt](null)
      val start = new CountDownLatch(1)
      val producers = (1 to 2).map { index =>
        new Thread(() => {
          awaitLatch(start)
          (1 to 200).foreach(_ => receipts.add(runtime.submit { ran.incrementAndGet(); () }))
        }, s"producer-$index")
      }
      val closer = new Thread(() => { awaitLatch(start); closing.set(runtime.close()) }, "closer")
      (producers :+ closer).foreach(_.start())
      start.countDown()
      (producers :+ closer).foreach(_.join(timeout.toMillis))
      closing.get.awaitCompleted(timeout)
      val outcomes = receipts.asScala.toVector.map(receipt => Try(receipt.awaitCompleted(timeout)).isSuccess -> receipt.isAccepted)
      outcomes.length shouldBe 400
      outcomes.foreach { case (completed, accepted) => completed shouldBe accepted }
      outcomes.count(_._1) shouldBe ran.get
      val trace = events.asScala.toVector
      trace.last shouldBe "closed"
      trace.count(_.startsWith("turn")) shouldBe ran.get
      runtime.state shouldBe RuntimeState.Closed
    } finally pool.close()
  }

  it("leaves a shared, injected executor usable by another runtime and by managed tasks after one runtime closed") {
    val pool = new ThreadPool("shared-pool", 2)
    try {
      val closed = Runtime.scheduled(pool)
      val other = Runtime.scheduled(pool)
      closed.submit(()).awaitCompleted(timeout)
      closed.close().awaitCompleted(timeout)
      val delivered = new CountDownLatch(1)
      other.submit {
        other.taskScope().launch(pool)(_ => 1)(_ => delivered.countDown())
        ()
      }.awaitCompleted(timeout)
      awaitLatch(delivered) shouldBe true
      other.submit(()).awaitCompleted(timeout)
      pool.reported shouldBe Nil
    } finally pool.close()
  }

  it("reports a failing cleanup step and still finishes the close with the permit released and the context restored") {
    val pool = new ThreadPool("closure-failure", 1)
    try {
      val runtime = Runtime.scheduled(pool)
      val target = Runtime.scheduled(pool)
      val reported = new AtomicReference(Vector.empty[Throwable])
      runtime.registerUnhandledErrorCallback(err => reported.updateAndGet(_ :+ err))
      val boom = new IllegalStateException("stop failed")
      val targetVar = {
        val cell = new AtomicReference[Var[Int]](null)
        target.submit(cell.set(Var(0))).awaitCompleted(timeout)
        cell.get
      }
      val second = new AtomicReference[Bridge](null)
      runtime.submit {
        val source = EventStream.fromCustomSource[Int](start = (_, _, _, _) => (), stop = _ => throw boom)
        // The bridge is the source's only observer, so detaching it in the cleanup stops the source, whose stop throws.
        EventBridge(source, new com.raquo.airstream.eventbus.EventBus[Int])
        second.set(SignalBridge(Var(1).signal, targetVar))
      }.awaitCompleted(timeout)
      target.submit(()).awaitCompleted(timeout)
      runtime.close().awaitCompleted(timeout)
      reported.get.exists(_ eq boom) shouldBe true
      // The cleanup went on after the failing step: it detached the second bridge's source endpoint too, which is the only
      // way that bridge's full close can still complete once its source runtime is closed.
      second.get.close().awaitCompleted(timeout)
      val endpoints = new AtomicReference[Option[Int]](None)
      target.submit(endpoints.set(Some(target.bridgeEndpointCount))).awaitCompleted(timeout)
      endpoints.get shouldBe Some(0)
      runtime.holderName shouldBe None
      val outside = new AtomicReference[Runtime](null)
      val probed = new CountDownLatch(1)
      pool.execute(() => { outside.set(Runtime.current); probed.countDown() })
      awaitLatch(probed) shouldBe true
      outside.get shouldBe Runtime.default
    } finally pool.close()
  }

  it("turns a dispatched runtime's own thread away after the close") {
    val dispatcher = new ThreadPool("dispatcher", 1)
    try {
      val outcome = new AtomicReference[Option[Throwable]](None)
      val done = new CountDownLatch(1)
      dispatcher.execute { () =>
        val runtime = Runtime.dispatched(scala.concurrent.ExecutionContext.parasitic)
        runtime.run(Var(0))
        runtime.close()
        outcome.set(Try(runtime.run(())).failed.toOption)
        done.countDown()
      }
      awaitLatch(done) shouldBe true
      outcome.get.map(_.getClass) shouldBe Some(classOf[ClosedRuntimeEntry])
    } finally dispatcher.close()
  }
}
