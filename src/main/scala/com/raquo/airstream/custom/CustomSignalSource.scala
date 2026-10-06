package com.raquo.airstream.custom

import com.raquo.airstream.core.{Signal, Transaction, WritableSignal}
import com.raquo.airstream.custom.CustomSource._

import scala.util.{Success, Try}

// @TODO[Test] needs testing

/** Use this to easily create a custom signal from an external source
  *
  * See docs on custom sources, and [[CustomSource.Config]]
  */
class CustomSignalSource[A](
  getInitialValue: => Try[A],
  makeConfig: (SetCurrentValue[A], GetCurrentValue[A], GetStartIndex, GetIsStarted) => CustomSource.Config
) extends WritableSignal[A] with CustomSource[A] {

  // The setter is the source's ingress: called from any thread, it delivers the value through the runtime.
  override protected val config: Config = makeConfig(
    value => runtime.deliver(setInTransaction(value)),
    () => tryNow(),
    () => startIndex,
    () => isStarted
  )

  /** Write `value` in a new transaction; runs inside the runtime. */
  private def setInTransaction(value: Try[A]): Unit = {
    Transaction(runtime)(fireTry(value, _))
  }

  override protected def currentValueFromParent(): Try[A] = getInitialValue
}

object CustomSignalSource {

  @deprecated("Use Signal.fromCustomSource", "15.0.0-M1")
  def apply[A](
    initial: => A
  )(
    config: (SetCurrentValue[A], GetCurrentValue[A], GetStartIndex, GetIsStarted) => Config
  ): Signal[A] = {
    new CustomSignalSource[A](Success(initial), config)
  }

  @deprecated("Use Signal.fromCustomSource", "15.0.0-M1")
  def fromTry[A](
    initial: => Try[A]
  )(
    config: (SetCurrentValue[A], GetCurrentValue[A], GetStartIndex, GetIsStarted) => Config
  ): Signal[A] = {
    new CustomSignalSource[A](initial, config)
  }
}
