package com.raquo.airstream.platform

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/** Pins down where Airstream's diagnostics go, for every platform it runs on.
  *
  * The debug operators and the unhandled-error reporter both need somewhere to say something. On a web page that is the
  * browser console and the choice makes itself. In a terminal application it does not: the program is very likely holding
  * the alternate screen, and a line written straight to stderr lands in the middle of the picture and corrupts it.
  *
  * So the destination is a value the consumer can replace rather than a call baked into the call site. That is the whole
  * design, and it is what these assertions are about — not the formatting of any particular message.
  */
class DevToolsSpec extends AnyFunSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit = DevTools.resetSink()

  describe("the diagnostic sink") {

    it("receives what was logged through it") {
      val seen = List.newBuilder[String]
      DevTools.setSink((level, prefix, value) => seen += s"$level|$prefix|$value")
      DevTools.log("here", 42)
      seen.result() shouldBe List("Log|here|42")
    }

    it("receives every message rather than only the first") {
      val seen = List.newBuilder[String]
      DevTools.setSink((_, prefix, _) => seen += prefix)
      DevTools.log("one", ())
      DevTools.log("two", ())
      seen.result() shouldBe List("one", "two")
    }

    it("receives warnings, so redirecting does not leave them on the screen") {
      val seen = List.newBuilder[String]
      DevTools.setSink((level, prefix, _) => seen += s"$level|$prefix")
      DevTools.warn("careful")
      seen.result() shouldBe List("Warn|careful")
    }

    it("receives errors, which is the case that matters most for a terminal application") {
      val seen = List.newBuilder[String]
      DevTools.setSink((level, prefix, _) => seen += s"$level|$prefix")
      DevTools.error("broken")
      seen.result() shouldBe List("Error|broken")
    }
  }

  describe("replacing the sink") {

    it("sends later messages to the new destination") {
      val first = List.newBuilder[String]
      val second = List.newBuilder[String]
      DevTools.setSink((_, prefix, _) => first += prefix)
      DevTools.log("early", ())
      DevTools.setSink((_, prefix, _) => second += prefix)
      DevTools.log("late", ())
      first.result() shouldBe List("early")
      second.result() shouldBe List("late")
    }

    it("is undone by resetting, which stops a test from leaking into the next one") {
      val seen = List.newBuilder[String]
      DevTools.setSink((_, prefix, _) => seen += prefix)
      DevTools.resetSink()
      DevTools.log("after reset", ())
      seen.result() shouldBe Nil
    }
  }

  describe("the default destination") {

    it("accepts a message without failing, whatever the platform decided it should be") {
      noException should be thrownBy DevTools.log("default", "value")
    }

    it("accepts a null value rather than assuming every payload is printable") {
      noException should be thrownBy DevTools.log("default", null)
    }
  }

  describe("the breakpoint") {

    it("is safe to call, so a debug operator left in the code does not take a program down") {
      noException should be thrownBy DevTools.breakpoint()
    }

    it("is safe to call more than once") {
      noException should be thrownBy { DevTools.breakpoint(); DevTools.breakpoint() }
    }
  }
}
