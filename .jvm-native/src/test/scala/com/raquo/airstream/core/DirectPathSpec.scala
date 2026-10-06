package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/** Ordinary synchronous graphs on the direct path submit nothing to an executor
  * and start no thread. A spying execution context is the only one in scope while the graphs run, so any submission would be
  * counted; the live-thread set is compared before and after, so a worker thread started on the way would show up by name.
  * The structural half (no lock, Future or executor in the production sources) is the verification sub-project's
  * `DirectPathSpec`.
  */
class DirectPathSpec extends UnitSpec {

  final private class SpyingContext extends ExecutionContext {
    val submissions = new AtomicInteger(0)
    override def execute(runnable: Runnable): Unit = {
      submissions.incrementAndGet()
      runnable.run()
    }
    override def reportFailure(cause: Throwable): Unit = throw cause
  }

  /** The live threads of this thread's group, by name and id; enumerated through the group because Scala Native has no
    * `Thread.getAllStackTraces`.
    */
  private def liveThreads: Set[String] = {
    val group = Thread.currentThread().getThreadGroup
    val threads = new Array[Thread](group.activeCount() * 2 + 8)
    val count = group.enumerate(threads, true)
    threads.take(count).filter(_ ne null).map(thread => s"${thread.getName}#${thread.getId}").toSet
  }

  it("runs synchronous graphs without an executor submission or a new thread") {
    val spy = new SpyingContext
    implicit val executionContext: ExecutionContext = spy
    val before = liveThreads
    val seen = mutable.Buffer[Any]()

    Seq(Runtime.default, Runtime.confined()).foreach { runtime =>
      runtime.run {
        val owner = new ManualOwner
        val a = Var(1)
        val b = Var(10)
        val bus = new EventBus[Int]
        val combined = a.signal.combineWith(b.signal).map { case (x, y) => x + y }
        val merged = EventStream.merge(bus.events, a.signal.updates)
        val sampled = bus.events.withCurrentValueOf(b.signal)
        val switched = bus.events.flatMapSwitch(n => if (n % 2 == 0) a.signal.updates else bus.events.map(_ * 100))
        combined.foreach(seen += _)(owner)
        merged.foreach(seen += _)(owner)
        sampled.foreach(seen += _)(owner)
        switched.foreach(seen += _)(owner)
        a.set(2)
        bus.emit(3)
        Var.set(a -> 5, b -> 50)
        bus.emit(4)
        a.update(_ + 1)
        owner.killSubscriptions()
        combined.foreach(seen += _)(owner)
        a.set(7)
        owner.killSubscriptions()
      }
    }

    seen.nonEmpty shouldBe true
    spy.submissions.get() shouldBe 0
    liveThreads.diff(before) shouldBe Set.empty
  }
}
