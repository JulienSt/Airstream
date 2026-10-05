package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.collection.mutable

/** Two confined runtimes on two threads share no transaction or startup state: work a thread does
  * on its own runtime runs on that thread, at once, whatever another runtime is in the middle of. Transactions and shared startup remain isolated across the two graphs.
  */
class RuntimeIsolationSpec extends UnitSpec {

  /** A gate one thread opens and another waits at; a stalled wait fails the test instead of hanging it. */
  private final class Gate(name: String) {
    private val latch = new CountDownLatch(1)
    def open(): Unit = latch.countDown()
    def awaitOpen(): Unit = assert(latch.await(5, TimeUnit.SECONDS), s"gate $name did not open")
  }

  /** The events of one run, in global order, each with the thread that recorded it. */
  private final class Log {
    private val sequence = new AtomicInteger(0)
    private val events = new AtomicReference(Vector.empty[(Int, String, String)])
    def record(label: String): Unit = {
      val n = sequence.incrementAndGet()
      events.updateAndGet(_ :+ ((n, Thread.currentThread().getName, label)))
      ()
    }
    def thread(label: String): Option[String] = events.get.find(_._3 == label).map(_._2)
    def isBefore(earlier: String, later: String): Boolean = {
      (events.get.find(_._3 == earlier), events.get.find(_._3 == later)) match {
        case (Some(e), Some(l)) => e._1 < l._1
        case _ => false
      }
    }
  }

  /** Run `body` on a named thread, keeping its failure for the asserting thread. */
  private final class Producer(name: String)(body: => Unit) {
    private val failure = new AtomicReference[Throwable](null)
    private val thread = new Thread(() => try body catch { case err: Throwable => failure.set(err) }, name)
    thread.setDaemon(true)
    thread.start()
    def join(): Unit = {
      thread.join(5000)
      assert(!thread.isAlive, s"$name did not finish")
      Option(failure.get()).foreach(err => throw err)
    }
  }

  it("runs B's root set on B's thread, at once, while A is paused inside an observer of its own runtime") {
    val log = new Log
    val aInside = new Gate("a-inside")
    val release = new Gate("release")
    val bNow = new AtomicReference(-1)

    val producerA = new Producer("producer-A")({
      val runtime = Runtime.confined()
      runtime.run {
        val owner = new ManualOwner
        val a = Var(0)
        a.signal.foreach { value =>
          if (value == 1) {
            log.record("a-observer")
            aInside.open()
            release.awaitOpen()
            log.record("a-observer-released")
          }
        }(owner)
        a.set(1)
      }
    })
    aInside.awaitOpen()

    val producerB = new Producer("producer-B")({
      val runtime = Runtime.confined()
      runtime.run {
        val owner = new ManualOwner
        val b = Var(0)
        b.signal.foreach { value =>
          if (value == 1) log.record("b-observer")
        }(owner)
        b.set(1)
        log.record("b-set-returned")
        bNow.set(b.now())
      }
    })
    producerB.join()
    release.open()
    producerA.join()

    log.thread("b-observer") shouldBe Some("producer-B")
    log.isBefore("b-observer", "b-set-returned") shouldBe true
    bNow.get() shouldBe 1
  }

  it("keeps B's start event and root set out of A's paused shared start") {
    val log = new Log
    val aInside = new Gate("a-inside")
    val release = new Gate("release")
    val bValues = mutable.Buffer[Int]()
    val bNow = new AtomicReference(-1)

    val producerA = new Producer("producer-A")({
      val runtime = Runtime.confined()
      runtime.run {
        val owner = new ManualOwner
        val stream = EventStream.fromCustomSource[Int](
          start = (fire, _, _, _) => {
            log.record("a-start")
            aInside.open()
            release.awaitOpen()
            fire(1)
          },
          stop = _ => ()
        )
        stream.addObserver(Observer[Int](_ => log.record("a-observer")))(owner)
        log.record("a-subscribe-returned")
      }
    })
    aInside.awaitOpen()

    val producerB = new Producer("producer-B")({
      val runtime = Runtime.confined()
      runtime.run {
        val owner = new ManualOwner
        val bVar = Var(0)
        EventStream.fromValue(42).addObserver(Observer[Int] { value =>
          bValues += value
          log.record("b-observer")
        })(owner)
        log.record("b-subscribe-returned")
        bVar.set(5)
        bNow.set(bVar.now())
      }
    })
    producerB.join()
    release.open()
    producerA.join()

    log.thread("b-observer") shouldBe Some("producer-B")
    log.isBefore("b-observer", "b-subscribe-returned") shouldBe true
    bValues.toList shouldBe List(42)
    bNow.get() shouldBe 5
    log.isBefore("b-subscribe-returned", "a-observer") shouldBe true
  }
}
