package com.raquo.airstream.ownership

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.{Receipt, Runtime}
import com.raquo.airstream.fixtures.ThreadPool

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicIntegerArray, AtomicReference}
import scala.collection.mutable
import scala.concurrent.duration._

/** On real threads (JVM and Native): producers on several threads cannot corrupt an owner's traversal, because every
  * change to the owner is a turn of the owner's scheduled runtime. Three producers create possessions and kill some of
  * their own externally while a fourth thread asks the owner to kill everything, again and again, all through `submit`;
  * at the end every possession's cleanup has run exactly once and nothing was reported as unhandled.
  */
class OwnerCleanupThreadsSpec extends UnitSpec {

  private val timeout = 10.seconds

  it("runs every cleanup exactly once while producers create and kill possessions and another thread kills the owner") {
    val pool = new ThreadPool("tw21", 3)
    try {
      val runtime = Runtime.scheduled(pool, capacity = 10000, turnsPerDrain = 4)
      val unhandled = new ConcurrentLinkedQueue[Throwable]()
      val perProducer = 300
      val producers = 3
      val cleanups = new AtomicIntegerArray(producers * perProducer)
      val receipts = new ConcurrentLinkedQueue[Receipt]()
      // Created and read only inside the runtime's turns, which never overlap.
      val subscriptions = mutable.Map[Int, Subscription]()
      val holder = new AtomicReference[ManualOwner]()
      runtime.submit {
        runtime.registerUnhandledErrorCallback(err => { unhandled.add(err); () })
        holder.set(new ManualOwner)
      }.awaitCompleted(timeout)
      val ownerInRuntime = holder.get
      ownerInRuntime.runtime shouldBe runtime

      val threads = (0 until producers).map { producer =>
        new Thread(
          () =>
            (0 until perProducer).foreach { step =>
              val id = producer * perProducer + step
              receipts.add(runtime.submit {
                subscriptions(id) = new Subscription(ownerInRuntime, () => { cleanups.incrementAndGet(id); () })
              })
              if (step % 7 == 6) {
                val victim = id - 3
                receipts.add(runtime.submit {
                  subscriptions.get(victim).filterNot(_.isKilled).foreach(_.kill())
                })
              }
            },
          s"tw21-producer-$producer"
        )
      } :+ new Thread(
        () => (1 to 40).foreach(_ => receipts.add(runtime.submit(ownerInRuntime.killSubscriptions()))),
        "tw21-killer"
      )
      threads.foreach(_.start())
      threads.foreach(_.join(timeout.toMillis))
      receipts.forEach(_.awaitCompleted(timeout))
      runtime.submit(ownerInRuntime.killSubscriptions()).awaitCompleted(timeout)

      val counts = (0 until cleanups.length()).map(cleanups.get)
      counts.count(_ != 1) shouldBe 0
      unhandled.isEmpty shouldBe true
      pool.reported shouldBe Nil
    } finally {
      pool.close()
    }
  }
}
