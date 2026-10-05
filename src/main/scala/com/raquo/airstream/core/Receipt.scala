package com.raquo.airstream.core

import scala.concurrent.Future

/** The acknowledgements of one submitted command.
  *
  * Admission and completion are distinct events, so they are two futures. `accepted` settles when `submit`
  * returns: successfully when the command was admitted into the runtime, with `SubmissionRejected` when it was turned
  * away at capacity, after the runtime failed or closed, or because a direct runtime could not run it on the calling
  * thread. A rejected command never runs and has no graph effect; a retry is a new `submit` and gets a
  * new id.
  *
  * `completed` settles once: successfully after the command's turn has drained, meaning the command returned and every
  * transaction it created, with their descendants and the observer removals due at their safe points, has run; with the
  * command's own exception when the command threw; with `RuntimeFailed` when the runtime failed before the command could
  * run. It does not wait for timers or external tasks the command started, and it says nothing about errors the graph
  * delivered through its error channel meanwhile: those stay reactive errors.
  *
  * Waiting on `completed` is for callers outside every runtime. On platforms with threads, `awaitCompleted` refuses to
  * block from inside a turn.
  */
final class Receipt private[airstream] (
  val id: Long,
  val accepted: Future[Unit],
  val completed: Future[Unit]
) extends ReceiptPlatformOps {

  /** True when admission accepted the command. Known as soon as `submit` returns. */
  def isAccepted: Boolean = accepted.value.exists(_.isSuccess)
}
