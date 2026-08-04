package com.raquo.airstream.timing

import com.raquo.airstream.core.{EventStream, Observer, Signal}
import com.raquo.airstream.fixtures.TestableOwner
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}

/** Exercises the Future-facing public API without a JavaScript promise or a background thread. */
class NativeFutureSpec extends AnyFunSpec with Matchers {

  final private class PumpExecutionContext extends ExecutionContext {
    private val pending = mutable.Queue[Runnable]()

    override def execute(runnable: Runnable): Unit = pending.enqueue(runnable)

    override def reportFailure(cause: Throwable): Unit = throw cause

    def runAll(): Int = {
      var count = 0
      while (pending.nonEmpty) {
        pending.dequeue().run()
        count += 1
      }
      count
    }
  }

  describe("EventStream.fromFuture") {

    it("emits a future that completed before the observer was attached") {
      val ec = new PumpExecutionContext
      implicit val executionContext: ExecutionContext = ec
      val owner = new TestableOwner
      val seen = mutable.Buffer[Int]()

      EventStream.fromFuture(Future.successful(42)).foreach(seen += _)(owner)

      seen shouldBe empty
      ec.runAll() should be > 0
      seen shouldBe mutable.Buffer(42)
      owner.killSubscriptions()
    }

    it("emits a pending future after its completion is pumped") {
      val ec = new PumpExecutionContext
      implicit val executionContext: ExecutionContext = ec
      val owner = new TestableOwner
      val promise = Promise[Int]()
      val seen = mutable.Buffer[Int]()

      EventStream.fromFuture(promise.future).foreach(seen += _)(owner)
      ec.runAll() shouldBe 0
      promise.success(7)
      seen shouldBe empty
      ec.runAll() should be > 0
      seen shouldBe mutable.Buffer(7)
      owner.killSubscriptions()
    }

    it("routes a failed future through the observer error channel") {
      val ec = new PumpExecutionContext
      implicit val executionContext: ExecutionContext = ec
      val owner = new TestableOwner
      val failure = new Exception("future failed")
      val errors = mutable.Buffer[Throwable]()

      EventStream.fromFuture(Future.failed[Int](failure)).addObserver(Observer.withRecover(_ => (), errors += _))(owner)
      ec.runAll()

      errors shouldBe mutable.Buffer(failure)
      owner.killSubscriptions()
    }

    it("evaluates the by-name future only when the stream first starts") {
      val ec = new PumpExecutionContext
      implicit val executionContext: ExecutionContext = ec
      val owner = new TestableOwner
      var evaluations = 0
      val stream = EventStream.fromFuture {
        evaluations += 1
        Future.successful(1)
      }

      evaluations shouldBe 0
      stream.foreach(_ => ())(owner)
      evaluations shouldBe 1
      ec.runAll()
      owner.killSubscriptions()
    }
  }

  describe("Signal.fromFuture") {

    it("starts at None and changes to Some when the future completes") {
      val ec = new PumpExecutionContext
      implicit val executionContext: ExecutionContext = ec
      val owner = new TestableOwner
      val promise = Promise[Int]()
      val signal = Signal.fromFuture(promise.future)
      val seen = mutable.Buffer[Option[Int]]()

      signal.now() shouldBe None
      signal.foreach(seen += _)(owner)
      seen shouldBe mutable.Buffer(None)
      promise.success(9)
      ec.runAll()

      signal.now() shouldBe Some(9)
      seen shouldBe mutable.Buffer(None, Some(9))
      owner.killSubscriptions()
    }

    it("uses the supplied initial value until the future completes") {
      val ec = new PumpExecutionContext
      implicit val executionContext: ExecutionContext = ec
      val owner = new TestableOwner
      val promise = Promise[Int]()
      val signal = Signal.fromFuture(promise.future, initial = 5)
      val seen = mutable.Buffer[Int]()

      signal.foreach(seen += _)(owner)
      seen shouldBe mutable.Buffer(5)
      promise.success(12)
      ec.runAll()

      signal.now() shouldBe 12
      seen shouldBe mutable.Buffer(5, 12)
      owner.killSubscriptions()
    }

    it("evaluates its by-name future once when first observed") {
      val ec = new PumpExecutionContext
      implicit val executionContext: ExecutionContext = ec
      val firstOwner = new TestableOwner
      val secondOwner = new TestableOwner
      var evaluations = 0
      val signal = Signal.fromFuture {
        evaluations += 1
        Future.successful(3)
      }

      evaluations shouldBe 0
      signal.foreach(_ => ())(firstOwner)
      evaluations shouldBe 1
      ec.runAll()
      firstOwner.killSubscriptions()
      signal.foreach(_ => ())(secondOwner)
      ec.runAll()

      evaluations shouldBe 1
      secondOwner.killSubscriptions()
    }

    it("remembers the completed value for an observer attached after a restart") {
      val ec = new PumpExecutionContext
      implicit val executionContext: ExecutionContext = ec
      val firstOwner = new TestableOwner
      val secondOwner = new TestableOwner
      val signal = Signal.fromFuture(Future.successful(21))
      val firstSeen = mutable.Buffer[Option[Int]]()
      val secondSeen = mutable.Buffer[Option[Int]]()

      signal.foreach(firstSeen += _)(firstOwner)
      ec.runAll()
      firstOwner.killSubscriptions()
      signal.foreach(secondSeen += _)(secondOwner)

      firstSeen shouldBe mutable.Buffer(None, Some(21))
      secondSeen shouldBe mutable.Buffer(Some(21))
      secondOwner.killSubscriptions()
    }
  }
}
