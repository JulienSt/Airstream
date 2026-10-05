package com.raquo.airstream.core

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** The blocking wait a receipt offers where there are threads to block. */
trait ReceiptPlatformOps { self: Receipt =>

  /** Block the calling thread until the command completed, failed, or `atMost` passed.
    *
    * Only a thread outside every runtime may wait: a turn that blocked on another command would hold its runtime's permit
    * while waiting for a runtime that may be waiting for it, so the wait is rejected with `WaitInsideTurn`
    * before it blocks.
    *
    * @throws com.raquo.airstream.core.AirstreamError.WaitInsideTurn when the calling thread is inside a turn
    * @throws java.util.concurrent.TimeoutException when the receipt did not settle within `atMost`
    * @throws Throwable the command's own exception, or `RuntimeFailed`, when the receipt settled as failed
    */
  def awaitCompleted(atMost: Duration): Unit = {
    Runtime.requireOutsideTurns(self)
    Await.result(completed, atMost)
    ()
  }
}
