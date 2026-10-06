package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.DeliveryRejected
import com.raquo.airstream.fixtures.ThreadPool

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

/** Managed tasks on real threads: the computation runs on a worker of the caller's pool, the scope's
  * decision runs in the runtime, and a result the runtime cannot take settles as rejected without losing the failure it
  * carried.
  */
class TaskScopeThreadsSpec extends UnitSpec {

  private val timeout = 5.seconds

  private def awaitLatch(latch: CountDownLatch): Boolean = latch.await(timeout.toMillis, TimeUnit.MILLISECONDS)

  it("suppresses generation one when it completes on a worker after generation two started, and delivers generation two") {
    val turns = new ThreadPool("task-turns", 1)
    val workers = new ThreadPool("task-workers", 2)
    try {
      val runtime = Runtime.scheduled(turns)
      val release = new CountDownLatch(1)
      val delivered = new AtomicReference(Vector.empty[Int])
      val tasks = new AtomicReference[(ManagedTask, ManagedTask)](null)
      runtime.submit {
        val scope = runtime.taskScope()
        val first = scope.launch(workers) { _ => awaitLatch(release); 1 }(result => delivered.updateAndGet(_ ++ result.toOption))
        scope.restart()
        val second = scope.launch(workers)(_ => 2)(result => delivered.updateAndGet(_ ++ result.toOption))
        tasks.set((first, second))
      }.awaitCompleted(timeout)
      val (first, second) = tasks.get
      Await.result(second.outcome, timeout) shouldBe TaskOutcome.Delivered
      release.countDown()
      Await.result(first.outcome, timeout) shouldBe TaskOutcome.Suppressed
      first.isCancelRequested shouldBe true
      delivered.get shouldBe Vector(2)
      (first.id < second.id) shouldBe true
    } finally {
      turns.close()
      workers.close()
    }
  }

  it("settles a task as rejected when its runtime cannot take the result, and reports the failure it carried") {
    val workers = new ThreadPool("task-workers", 1)
    try {
      val runtime = Runtime.confined()
      val reported = new AtomicReference(Vector.empty[Throwable])
      runtime.registerUnhandledErrorCallback(err => reported.updateAndGet(_ :+ err))
      val boom = new IllegalStateException("computation failed")
      val task = runtime.run(runtime.taskScope().launch[Int](workers)(_ => throw boom)(_ => ()))
      Await.result(task.outcome, timeout) match {
        case TaskOutcome.Rejected(_) => succeed
        case other => fail(s"expected a rejection, got $other")
      }
      val rejection = reported.get.collectFirst { case rejected: DeliveryRejected => rejected }
      rejection.map(_.getSuppressed.toList) shouldBe Some(List(boom))
    } finally workers.close()
  }
}
