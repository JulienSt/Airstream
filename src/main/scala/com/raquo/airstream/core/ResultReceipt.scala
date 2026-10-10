package com.raquo.airstream.core

import scala.concurrent.Future

/** Retain a submitted command's value together with its admission and completion acknowledgement.
  * The result waits for receipt completion so callers cannot observe a successful value before that submitted turn finishes.
  *
  * @param receipt admission and completion of the same command
  * @param result value returned by the command, or its execution or admission failure
  * @tparam A command result type
  */
final class ResultReceipt[+A] private[airstream] (
  val receipt: Receipt,
  val result: Future[A]
)
