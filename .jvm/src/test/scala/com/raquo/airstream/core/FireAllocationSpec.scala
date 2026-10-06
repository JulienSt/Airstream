package com.raquo.airstream.core

import com.raquo.airstream.AllocationMeter.bytesPerCall
import com.raquo.airstream.UnitSpec
import com.raquo.airstream.ownership.ManualOwner

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
}
