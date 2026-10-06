package com.raquo.airstream.custom

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.core.{EventStream, Runtime, Signal}
import com.raquo.airstream.fixtures.ThreadPool
import com.raquo.airstream.ownership.ManualOwner

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.util.{Success, Try}

/** A custom source fired from a thread outside its runtime: the fire callback is the source's ingress, so
  * a thread that does not hold the permit hands the event to the owning runtime instead of touching the graph. The valid
  * synchronous path, a fire from the start callback inside the turn, keeps the shared-start behaviour the shared specs pin.
  */
class CustomSourceIngressSpec extends UnitSpec {

  private val timeout = 5.seconds

  it("delivers an event a foreign thread fires into a scheduled runtime's custom source in a turn of that runtime") {
    val turns = new ThreadPool("turns", 1)
    try {
      val runtime = Runtime.scheduled(turns)
      val fireCell = new AtomicReference[Int => Unit](null)
      val seen = new AtomicReference(Vector.empty[(Int, String)])
      runtime.submit {
        EventStream
          .fromCustomSource[Int](start = (fire, _, _, _) => fireCell.set(fire), stop = _ => ())
          .foreach(value => seen.updateAndGet(_ :+ ((value, Thread.currentThread.getName))))(new ManualOwner)
      }.awaitCompleted(timeout)
      fireCell.get()(5)
      runtime.submit(()).awaitCompleted(timeout)
      seen.get.map(_._1) shouldBe Vector(5)
      turns.threadNames.contains(seen.get.head._2) shouldBe true
    } finally turns.close()
  }

  it("delivers a value a foreign thread sets into a scheduled runtime's custom signal in a turn of that runtime") {
    val turns = new ThreadPool("turns", 1)
    try {
      val runtime = Runtime.scheduled(turns)
      val setCell = new AtomicReference[Try[Int] => Unit](null)
      val seen = new AtomicReference(Vector.empty[(Int, String)])
      runtime.submit {
        Signal
          .fromCustomSource[Int](Success(0), start = (setValue, _, _, _) => setCell.set(setValue), stop = _ => ())
          .foreach(value => seen.updateAndGet(_ :+ ((value, Thread.currentThread.getName))))(new ManualOwner)
      }.awaitCompleted(timeout)
      setCell.get()(Success(5))
      runtime.submit(()).awaitCompleted(timeout)
      seen.get.map(_._1) shouldBe Vector(0, 5)
      turns.threadNames.contains(seen.get.last._2) shouldBe true
    } finally turns.close()
  }
}
