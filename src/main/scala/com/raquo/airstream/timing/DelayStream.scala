package com.raquo.airstream.timing

import com.raquo.airstream.common.{InternalNextErrorObserver, SingleParentStream}
import com.raquo.airstream.core.{EventStream, Transaction}
import com.raquo.airstream.platform.{undefined, Timers, UndefOr}
import com.raquo.airstream.platform.JsArray

class DelayStream[A](
  override protected val parent: EventStream[A],
  delayMs: Int
) extends SingleParentStream[A, A] with InternalNextErrorObserver[A] {

  /** Async stream, so reset rank */
  override protected val topoRank: Int = 1

  private val timerHandles: JsArray[Timers.TimerHandle] = JsArray()

  override protected def onNext(nextValue: A, transaction: Transaction): Unit = {
    var timerHandle: UndefOr[Timers.TimerHandle] = undefined
    timerHandle = Timers.setTimeout(delayMs.toDouble) {
      // println(s"> init trx from DelayEventStream.onNext($nextValue)")
      timerHandle.foreach(handle => timerHandles.splice(timerHandles.indexOf(handle), deleteCount = 1))
      Transaction(fireValue(nextValue, _))
      ()
    }
    timerHandles.push(timerHandle.get)
  }

  override def onError(nextError: Throwable, transaction: Transaction): Unit = {
    var timerHandle: UndefOr[Timers.TimerHandle] = undefined
    timerHandle = Timers.setTimeout(delayMs.toDouble) {
      timerHandle.foreach(handle => timerHandles.splice(timerHandles.indexOf(handle), deleteCount = 1))
      Transaction(fireError(nextError, _))
      ()
    }
    timerHandles.push(timerHandle.get)
  }

  override protected def onStop(): Unit = {
    timerHandles.forEach(Timers.clearTimeout(_))
    timerHandles.length = 0 // Clear array
    super.onStop()
  }
}
