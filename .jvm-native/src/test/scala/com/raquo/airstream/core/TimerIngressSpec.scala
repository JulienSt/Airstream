package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.AirstreamError.{ForeignThreadEntry, ScheduledRuntimeEntry}
import com.raquo.airstream.fixtures.ThreadPool

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration._

/** Timer readiness on JVM and Native: the queue, the clock and the handles are runtime
  * state, touched only under the permit. A host thread outside a scheduled runtime reports readiness as a submitted
  * command; a pump that would enter the runtime directly is rejected before it touches the queue.
  */
class TimerIngressSpec extends UnitSpec {

  private val timeout = 5.seconds

  private def withScheduled[A](body: Runtime => A): A = {
    val pool = new ThreadPool("timer-turns", 1)
    try body(Runtime.scheduled(pool)) finally pool.close()
  }

  /** Read `read` inside a turn of `runtime`, from outside every runtime. */
  private def inTurn[A](runtime: Runtime)(read: => A): A = {
    val cell = new AtomicReference[Option[A]](None)
    runtime.submit(cell.set(Some(read))).awaitCompleted(timeout)
    cell.get.get
  }

  it("rejects a direct pump of a scheduled runtime's timers before it touches the queue: the due timer stays pending") {
    withScheduled { runtime =>
      val fired = new AtomicInteger(0)
      inTurn(runtime)(runtime.timers.setTimeout(5)(fired.incrementAndGet()))
      assertThrows[ScheduledRuntimeEntry](runtime.timers.runDue(10))
      inTurn(runtime)(runtime.timers.pendingCount) shouldBe 1
      fired.get shouldBe 0
    }
  }

  it("fires a scheduled runtime's due timers in a turn when a host thread reports readiness by submission") {
    withScheduled { runtime =>
      val fired = new AtomicReference(Vector.empty[String])
      inTurn(runtime)(runtime.timers.setTimeout(5)(fired.updateAndGet(_ :+ Thread.currentThread.getName)))
      runtime.timers.submitDue(10).awaitCompleted(timeout)
      fired.get.map(_.startsWith("timer-turns")) shouldBe Vector(true)
      inTurn(runtime)(runtime.timers.pendingCount) shouldBe 0
    }
  }

  it("rejects scheduling a timer on a confined runtime from a foreign thread before it touches the queue") {
    val runtime = Runtime.confined()
    val outcome = new AtomicReference[Option[Throwable]](None)
    val foreign = new Thread(() => {
      outcome.set(scala.util.Try(runtime.timers.setTimeout(5)(())).failed.toOption)
    }, "foreign-timer-caller")
    foreign.start()
    foreign.join(timeout.toMillis)
    outcome.get.map(_.getClass) shouldBe Some(classOf[ForeignThreadEntry])
    runtime.timers.pendingCount shouldBe 0
  }
}
