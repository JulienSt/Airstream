package com.raquo.airstream.errors

import com.raquo.airstream.core.{AirstreamError, Observer}
import com.raquo.airstream.core.AirstreamError.{ObserverError, ObserverErrorHandlingError}
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.fixtures.TestableOwner
import com.raquo.airstream.platform.{DevTools, DiagnosticLevel, Timers}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.collection.mutable

/** Native unhandled-error contracts, including the replaceable route a terminal UI needs. */
class NativeUnhandledErrorSpec extends AnyFunSpec with Matchers {

  private type Diagnostic = (DiagnosticLevel, String, Any)

  private def withCapturedDiagnostics(run: mutable.Buffer[Diagnostic] => Unit): Unit = {
    val diagnostics = mutable.Buffer.empty[Diagnostic]
    DevTools.setSink((level, message, value) => diagnostics += ((level, message, value)))
    try run(diagnostics)
    finally {
      DevTools.resetSink()
      Timers.clearAll()
    }
  }

  describe("unhandled error delivery") {

    it("delivers the full unhandled error to a consumer sink") {
      withCapturedDiagnostics { diagnostics =>
        val failure = new IllegalStateException("lost-no-more")
        AirstreamError.sendUnhandledError(failure)

        diagnostics.map(_._1) should contain(DiagnosticLevel.Error)
        diagnostics.map(_._2).mkString("\n") should include("lost-no-more")
      }
    }

    it("keeps delayed rethrow observable until the Native event loop pumps it") {
      Timers.clearAll()
      val failure = new IllegalStateException("rethrow-me")
      AirstreamError.delayedRethrowErrorCallback(failure)

      Timers.pendingCount shouldBe 1
      val thrown = intercept[IllegalStateException](Timers.runDue(0))
      thrown should be theSameInstanceAs failure
    }
  }

  describe("terminal-safe routing") {

    it("writes an unhandled error only to the installed sink, not to stderr") {
      val stderr = new ByteArrayOutputStream()
      Console.withErr(new PrintStream(stderr)) {
        withCapturedDiagnostics { diagnostics =>
          AirstreamError.consoleErrorCallback(new RuntimeException("paint-stays-intact"))
          diagnostics.map(_._1) shouldBe mutable.Buffer(DiagnosticLevel.Error)
          diagnostics.head._2 should include("paint-stays-intact")
        }
      }
      stderr.toString shouldBe empty
    }

    it("routes callback-reporting failures through the sink without touching stderr") {
      val stderr = new ByteArrayOutputStream()
      val brokenCallback: Throwable => Unit = _ => throw new RuntimeException("callback-broke")
      Console.withErr(new PrintStream(stderr)) {
        withCapturedDiagnostics { diagnostics =>
          AirstreamError.registerUnhandledErrorCallback(brokenCallback)
          try AirstreamError.sendUnhandledError(new RuntimeException("original"))
          finally AirstreamError.unregisterUnhandledErrorCallback(brokenCallback)

          diagnostics.map(_._1) should contain allOf (DiagnosticLevel.Error, DiagnosticLevel.Warn)
          Timers.pendingCount shouldBe 1
        }
      }
      stderr.toString shouldBe empty
    }

    it("honours a replacement sink immediately") {
      val first = mutable.Buffer.empty[Diagnostic]
      val second = mutable.Buffer.empty[Diagnostic]
      DevTools.setSink((level, message, value) => first += ((level, message, value)))
      try {
        DevTools.warn("first")
        DevTools.setSink((level, message, value) => second += ((level, message, value)))
        DevTools.error("second")

        first.map(_._2) shouldBe mutable.Buffer("first")
        second.map(_._2) shouldBe mutable.Buffer("second")
      } finally DevTools.resetSink()
    }
  }

  describe("observer isolation") {

    it("continues delivering current and later events after one observer throws") {
      withCapturedDiagnostics { _ =>
        implicit val owner: TestableOwner = new TestableOwner
        val errors = mutable.Buffer.empty[Throwable]
        val survivor = mutable.Buffer.empty[Int]
        val collectError: Throwable => Unit = errors += _
        AirstreamError.registerUnhandledErrorCallback(collectError)
        try {
          val bus = new EventBus[Int]
          bus.events.addObserver(Observer[Int](_ => throw new RuntimeException("observer-next")))
          bus.events.addObserver(Observer[Int](survivor += _))

          bus.emit(1)
          bus.emit(2)

          survivor shouldBe mutable.Buffer(1, 2)
          errors.collect { case error: ObserverError => error }.size shouldBe 2
        } finally {
          AirstreamError.unregisterUnhandledErrorCallback(collectError)
          owner.killSubscriptions()
        }
      }
    }

    it("continues the graph when an observer's recovery callback also throws") {
      withCapturedDiagnostics { _ =>
        implicit val owner: TestableOwner = new TestableOwner
        val errors = mutable.Buffer.empty[Throwable]
        val survivor = mutable.Buffer.empty[Int]
        val collectError: Throwable => Unit = errors += _
        AirstreamError.registerUnhandledErrorCallback(collectError)
        try {
          val bus = new EventBus[Int]
          val broken = Observer.withRecover[Int](
            _ => throw new RuntimeException("observer-next"),
            { case _ => throw new RuntimeException("observer-recovery") }
          )
          bus.events.addObserver(broken)
          bus.events.addObserver(Observer[Int](survivor += _))

          bus.emit(1)
          bus.emit(2)

          survivor shouldBe mutable.Buffer(1, 2)
          errors.collect { case error: ObserverErrorHandlingError => error }.size shouldBe 2
        } finally {
          AirstreamError.unregisterUnhandledErrorCallback(collectError)
          owner.killSubscriptions()
        }
      }
    }
  }
}
