package com.raquo.airstream.core

/** The observation points a runtime reports to a test: the permit changing hands, the context
  * being restored on exit, turns starting and completing, transactions being created, observer removals being requested
  * and applied, and the runtime failing.
  *
  * A runtime reports to one trace installed with `Runtime.traceTo`, or to none, which costs the hot path a null check per
  * event and nothing else. Every method does nothing by default so a test overrides only what it reads. A trace is
  * called from whichever thread the event happens on and must record without taking a lock that would serialise the
  * runtimes it watches: the recording must not become the synchronisation that makes an unsafe schedule pass.
  */
trait RuntimeTrace {

  /** A thread took `runtime`'s permit: an outermost `run` or a drainer's turn. */
  def permitAcquired(runtime: Runtime): Unit = ()

  /** The holding thread gave `runtime`'s permit back. */
  def permitReleased(runtime: Runtime): Unit = ()

  /** The exiting thread restored the context it had before entering `runtime`, on a normal or a throwing exit. */
  def contextRestored(runtime: Runtime): Unit = ()

  /** The turn of the command `commandId` started on the current thread. */
  def turnStarted(runtime: Runtime, commandId: Long): Unit = ()

  /** The turn of the command `commandId` drained; its receipt settles right after. */
  def turnCompleted(runtime: Runtime, commandId: Long): Unit = ()

  /** A transaction was created in `runtime`, inside the turn of `commandId` when there is one. */
  def transactionCreated(runtime: Runtime, commandId: Option[Long]): Unit = ()

  /** The removal of an external observer was requested; it is applied now or at the next safe point. */
  def removalRequested(runtime: Runtime, observer: Observer[?]): Unit = ()

  /** An external observer was removed from its observable. */
  def removalApplied(runtime: Runtime, observer: Observer[?]): Unit = ()

  /** `runtime` moved to `RuntimeState.Failed` because of `cause`. */
  def runtimeFailed(runtime: Runtime, cause: Throwable): Unit = ()
}
