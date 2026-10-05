package com.raquo.airstream.core

/** Where a runtime is in its lifecycle.
  *
  * A direct runtime is `Open` until it is closed. A scheduled runtime moves to `Failed` when its executor rejects a drain
  * after commands were accepted, or when a turn leaves its transaction engine torn: it then admits nothing, settles every
  * queued receipt as failed and never runs another turn, because continuing on known-corrupt state is not a recovery.
  * `Closing` and `Closed` are the graceful shutdown of a runtime.
  */
sealed abstract class RuntimeState

object RuntimeState {

  /** Admitting commands. */
  case object Open extends RuntimeState

  /** Admission closed; accepted work still finishes. */
  case object Closing extends RuntimeState

  /** Accepted work settled and resources released. */
  case object Closed extends RuntimeState

  /** The scheduler could not continue; accepted work was settled as failed. */
  case object Failed extends RuntimeState
}
