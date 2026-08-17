package com.raquo.airstream.platform

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/** Pins down the keyed store Airstream's transaction bookkeeping is built on, for every platform it runs on.
  *
  * `Transaction` uses one to remember which child transactions belong to which parent. The set is rewritten constantly
  * while a graph is firing, which is why it is a mutable map rather than an immutable one being rebuilt each time.
  *
  * Iteration order matters here and is therefore asserted. Nested transactions have to run in the order they were created
  * or the graph fires in the wrong sequence, and a map that iterates by hash would break that in a way that only shows up
  * under load.
  *
  * As with the array and the optional, this spec lives in the shared tree so the same assertions run against the
  * JavaScript type and against the replacement.
  */
class JsMapSpec extends AnyFunSpec with Matchers {

  describe("an empty map") {

    it("reports size zero") {
      new JsMap[String, Int]().size shouldBe 0
    }

    it("finds nothing under any key") {
      new JsMap[String, Int]().get("absent").isEmpty shouldBe true
    }
  }

  describe("set and get") {

    it("finds a value under the key it was stored with") {
      val map = new JsMap[String, Int]()
      map.set("a", 1)
      map.get("a").getOrElse(0) shouldBe 1
    }

    it("does not find it under a different key") {
      val map = new JsMap[String, Int]()
      map.set("a", 1)
      map.get("b").isEmpty shouldBe true
    }

    it("replaces rather than duplicates when the same key is set twice") {
      val map = new JsMap[String, Int]()
      map.set("a", 1)
      map.set("a", 2)
      map.get("a").getOrElse(0) shouldBe 2
      map.size shouldBe 1
    }
  }

  describe("a legitimate null value") {

    it("keeps null present instead of confusing it with a missing key") {
      val map = new JsMap[String, AnyRef]()
      map.set("present", null)
      map.get("present").isDefined shouldBe true
      map.get("present").get shouldBe null
    }

    it("still distinguishes another key that was never inserted") {
      val map = new JsMap[String, AnyRef]()
      map.set("present", null)
      map.get("missing").isEmpty shouldBe true
      map.size shouldBe 1
    }
  }

  describe("size") {

    it("counts each distinct key once") {
      val map = new JsMap[String, Int]()
      map.set("a", 1)
      map.set("b", 2)
      map.size shouldBe 2
    }

    it("drops back when a key is deleted") {
      val map = new JsMap[String, Int]()
      map.set("a", 1)
      map.delete("a")
      map.size shouldBe 0
    }
  }

  describe("delete") {

    it("removes the entry it names") {
      val map = new JsMap[String, Int]()
      map.set("a", 1)
      map.delete("a")
      map.get("a").isEmpty shouldBe true
    }

    it("leaves the other entries alone") {
      val map = new JsMap[String, Int]()
      map.set("a", 1)
      map.set("b", 2)
      map.delete("a")
      map.get("b").getOrElse(0) shouldBe 2
    }

    it("accepts a key that is not there, because a caller cannot always know") {
      val map = new JsMap[String, Int]()
      noException should be thrownBy map.delete("never added")
    }
  }

  describe("forEach") {

    it("visits every entry, handing over the value and its key") {
      val map = new JsMap[String, Int]()
      map.set("a", 1)
      map.set("b", 2)
      val seen = List.newBuilder[(String, Int)]
      map.forEach((value, key) => seen += (key -> value))
      seen.result().toSet shouldBe Set("a" -> 1, "b" -> 2)
    }

    it("visits nothing at all for an empty map") {
      val seen = List.newBuilder[String]
      new JsMap[String, Int]().forEach((_, key) => seen += key)
      seen.result() shouldBe Nil
    }

    it("visits in insertion order, which is what nested transactions depend on") {
      val map = new JsMap[String, Int]()
      Seq("first", "second", "third", "fourth").zipWithIndex.foreach { case (key, index) =>
        map.set(key, index)
      }
      val seen = List.newBuilder[String]
      map.forEach((_, key) => seen += key)
      seen.result() shouldBe List("first", "second", "third", "fourth")
    }

    it("keeps insertion order even after a middle entry is deleted and re-added at the end") {
      val map = new JsMap[String, Int]()
      Seq("a", "b", "c").zipWithIndex.foreach { case (key, index) => map.set(key, index) }
      map.delete("b")
      map.set("b", 9)
      val seen = List.newBuilder[String]
      map.forEach((_, key) => seen += key)
      seen.result() shouldBe List("a", "c", "b")
    }
  }
}
