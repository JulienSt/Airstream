package com.raquo.airstream.core

import com.raquo.airstream.UnitSpec
import com.raquo.airstream.ownership.ManualOwner

import scala.collection.mutable

/** Characterizes the public factory while its general custom-source configuration is still in place. */
class FromValueLifecycleSpec extends UnitSpec {

  Seq(false, true).foreach { emitOnce =>
    it(s"preserves restart and additional-observer behavior with emitOnce=$emitOnce") {
      val source = EventStream.fromValue(42, emitOnce)
      val seen = mutable.Buffer.empty[(String, Int)]
      val first = new ManualOwner
      val second = new ManualOwner
      source.foreach(value => seen += "first" -> value)(first)
      source.foreach(value => seen += "second" -> value)(second)
      seen.toList shouldBe List("first" -> 42)
      first.killSubscriptions()
      second.killSubscriptions()
      val restarted = new ManualOwner
      source.foreach(value => seen += "restarted" -> value)(restarted)
      seen.toList shouldBe (if (emitOnce) List("first" -> 42) else List("first" -> 42, "restarted" -> 42))
      restarted.killSubscriptions()
    }

    it(s"suppresses a stopped shared-start callback and retains first-start semantics with emitOnce=$emitOnce") {
      val source = EventStream.fromValue(42, emitOnce)
      val seen = mutable.Buffer.empty[Int]
      val first = new ManualOwner
      Transaction.onStart.shared {
        source.foreach(seen += _)(first)
        seen.toList shouldBe Nil
        first.killSubscriptions()
      }
      seen.toList shouldBe Nil
      val restarted = new ManualOwner
      Transaction.onStart.shared {
        source.foreach(seen += _)(restarted)
        seen.toList shouldBe Nil
      }
      seen.toList shouldBe (if (emitOnce) Nil else List(42))
      restarted.killSubscriptions()
    }
  }
}
