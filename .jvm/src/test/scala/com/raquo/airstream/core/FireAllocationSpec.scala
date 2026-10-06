package com.raquo.airstream.core

import com.raquo.airstream.AllocationMeter.bytesPerCall
import com.raquo.airstream.UnitSpec
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

import scala.util.{Success, Try}

/** Handing one value to an observable's observers allocates nothing: the fire loops of `WritableStream` and
  * `WritableSignal` run once per node per event, so a closure over the value, the transaction or a captured flag there is an
  * object per node per event, which only the JIT's escape analysis removes, and only while the call site stays monomorphic.
  * The observers here do their own work without allocating, so what is measured is the loop itself.
  */
class FireAllocationSpec extends UnitSpec {

  final private class FiringStream extends WritableStream[Int] {

    override protected val topoRank: Int = 1

    override protected def onWillStart(): Unit = ()

    def fire(value: Int, transaction: Transaction): Unit = fireValue(value, transaction)

    def fireAsTry(value: Try[Int], transaction: Transaction): Unit = fireTry(value, transaction)
  }

  final private class FiringSignal extends WritableSignal[Int] {

    override protected val topoRank: Int = 1

    override protected def onWillStart(): Unit = ()

    override protected def currentValueFromParent(): Try[Int] = Success(0)

    def fire(value: Try[Int], transaction: Transaction): Unit = fireTry(value, transaction)
  }

  final private class CountingObserver extends Observer[Int] {

    var sum: Long = 0L

    override def onNext(nextValue: Int): Unit = sum += nextValue

    override def onError(nextError: Throwable): Unit = ()

    override def onTry(nextValue: Try[Int]): Unit = nextValue match {
      case Success(value) => sum += value
      case _ => ()
    }
  }

  final private class CountingInternalObserver extends InternalObserver[Int] {

    var sum: Long = 0L

    override protected def onNext(nextValue: Int, transaction: Transaction): Unit = sum += nextValue

    override protected def onError(nextError: Throwable, transaction: Transaction): Unit = ()

    override protected def onTry(nextValue: Try[Int], transaction: Transaction): Unit = nextValue match {
      case Success(value) => sum += value
      case _ => ()
    }
  }

  /** Bytes per fire, measured inside one transaction, which every fire is handed. */
  private def bytesPerFire(fire: Transaction => Unit): Double = {
    var bytes = Double.NaN
    Transaction { transaction =>
      bytes = bytesPerCall()(() => fire(transaction))
    }
    bytes
  }

  it("a stream hands a value to its external and internal observers without allocating") {
    val owner = new ManualOwner
    val stream = new FiringStream
    val external = new CountingObserver
    val internal = new CountingInternalObserver
    stream.addObserver(external)(owner)
    stream.addInternalObserver(internal, shouldCallMaybeWillStart = false)
    val bytes = bytesPerFire(stream.fire(1, _))
    owner.killSubscriptions()
    withClue(s"$bytes bytes per fire: ") {
      (bytes < 1.0) shouldBe true
    }
    (external.sum > 0L) shouldBe true
    (internal.sum > 0L) shouldBe true
  }

  it("a signal hands a value to its external and internal observers without allocating") {
    val owner = new ManualOwner
    val signal = new FiringSignal
    val external = new CountingObserver
    val internal = new CountingInternalObserver
    signal.addObserver(external)(owner)
    signal.addInternalObserver(internal, shouldCallMaybeWillStart = false)
    val next = Success(1)
    val bytes = bytesPerFire(signal.fire(next, _))
    owner.killSubscriptions()
    withClue(s"$bytes bytes per fire: ") {
      (bytes < 1.0) shouldBe true
    }
    (external.sum > 0L) shouldBe true
    (internal.sum > 0L) shouldBe true
  }

  // Handing on a `Try`: dispatching on success or failure with `Try.fold` or `Option.foreach` builds a closure per branch
  // before choosing one, on every event; a `match` builds none.

  it("a map node hands a value on allocating only the Success it stores") {
    val owner = new ManualOwner
    val source = new FiringSignal
    val nodes = 4
    val chain = (1 to nodes).foldLeft(source: Signal[Int])((signal, _) => signal.map(identity))
    val external = new CountingObserver
    chain.addObserver(external)(owner)
    val next = Success(1)
    val bytes = bytesPerFire(source.fire(next, _))
    owner.killSubscriptions()
    withClue(s"$bytes bytes per fire through $nodes map nodes: ") {
      (bytes / nodes <= 16.0) shouldBe true
    }
    (external.sum > 0L) shouldBe true
  }

  it("an observer, an internal observer and a stream take a Try without allocating") {
    val next = Success(1)
    val sum = Array(0L)
    val observer = Observer[Int](value => sum(0) += value)
    val observerBytes = bytesPerCall()(() => observer.onTry(next))
    val internal = InternalObserver[Int]((value, _) => sum(0) += value, (_, _) => ())
    val internalBytes = bytesPerFire(transaction => InternalObserver.onTry(internal, next, transaction))
    val owner = new ManualOwner
    val stream = new FiringStream
    stream.addObserver(new CountingObserver)(owner)
    val streamBytes = bytesPerFire(stream.fireAsTry(next, _))
    owner.killSubscriptions()
    withClue(s"Observer.onTry $observerBytes, InternalObserver.onTry $internalBytes, stream fireTry $streamBytes bytes: ") {
      (observerBytes < 1.0 && internalBytes < 1.0 && streamBytes < 1.0) shouldBe true
    }
  }

  it("a combine node records its parents' update ids without boxing them") {
    // The same events cost the same whether the update ids are small or large: a boxed id costs nothing only while the
    // JVM's cache of small Integers covers it, which ids leave after the first hundred updates of a runtime.
    val runtime = Runtime.confined()
    val (smallIds, largeIds) = runtime.run {
      val owner = new ManualOwner
      val left = Var(0)
      val right = Var(0)
      left.signal.combineWith(right.signal).foreach(_ => ())(owner)
      def bytesAfterId(lastId: Int): Double = {
        runtime.continueUpdateIdsAfter(lastId)
        bytesPerCall(calls = 40, warmup = 5)(() => left.set(1))
      }
      val measured = (bytesAfterId(-120), bytesAfterId(1000000))
      owner.killSubscriptions()
      measured
    }
    withClue(s"$smallIds bytes per event with small ids, $largeIds with large ones: ") {
      (largeIds <= smallIds) shouldBe true
    }
  }

  it("an EventBus writer handed a Try allocates no more than handed the value") {
    val owner = new ManualOwner
    val bus = new com.raquo.airstream.eventbus.EventBus[Int]
    bus.events.addObserver(new CountingObserver)(owner)
    val next = Success(1)
    val valueBytes = bytesPerCall()(() => bus.writer.onNext(1))
    val tryBytes = bytesPerCall()(() => bus.writer.onTry(next))
    owner.killSubscriptions()
    withClue(s"onNext $valueBytes, onTry $tryBytes bytes: ") {
      (tryBytes <= valueBytes) shouldBe true
    }
  }
}
