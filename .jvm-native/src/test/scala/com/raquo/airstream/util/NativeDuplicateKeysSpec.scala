package com.raquo.airstream.util

import com.raquo.airstream.platform.{DevTools, DiagnosticLevel}
import com.raquo.airstream.state.Var
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Native contract for duplicate detection: keys compare like JavaScript's `Set`, by reference for objects and by value for
  * strings, numbers and booleans, exactly as the Scala.js build does.
  */
class NativeDuplicateKeysSpec extends AnyFunSpec with Matchers {

  final private case class Key(name: String)

  describe("hasDuplicateKeys") {

    it("treats two equal but distinct objects as different keys") {
      val first  = Key("same")
      val second = Key("same")
      first shouldBe second
      hasDuplicateKeys(Seq(first, second))(identity) shouldBe false
    }

    it("treats the same object listed twice as a duplicate") {
      val only = Key("once")
      hasDuplicateKeys(Seq(only, only))(identity) shouldBe true
    }

    it("compares strings, numbers and booleans by value like JavaScript primitives") {
      hasDuplicateKeys(Seq(new String("text"), new String("text")))(identity) shouldBe true
      hasDuplicateKeys(Seq[Any](1, 1.0))(identity) shouldBe true
      hasDuplicateKeys(Seq[Any](Double.NaN, Double.NaN))(identity) shouldBe true
      hasDuplicateKeys(Seq[Any](0.0, -0.0))(identity) shouldBe true
      hasDuplicateKeys(Seq[Any](true, true))(identity) shouldBe true
      hasDuplicateKeys(Seq[Any](1, 2, "1"))(identity) shouldBe false
    }
  }

  describe("Var.set batches") {

    it("reject the same Var listed twice and accept two distinct Vars holding equal values") {
      val errors = mutable.Buffer.empty[String]
      DevTools.setSink((level, message, _) => if (level == DiagnosticLevel.Error) errors += message)
      try {
        val first  = Var(Key("state"))
        val second = Var(Key("state"))
        Var.set(first -> Key("next"), second -> Key("next"))
        errors shouldBe empty
        second.now() shouldBe Key("next")
        Var.set(first -> Key("a"), first -> Key("b"))
        errors.mkString("\n") should include("has duplicates")
        first.now() shouldBe Key("next")
      } finally DevTools.resetSink()
    }
  }
}
