package com.raquo.airstream.core

import com.raquo.airstream.platform.{undefined, UndefOr}

import scala.util.{Failure, Success, Try}

/** This trait exists to provide private functionality that can not be covariant in A.
  * Concrete classes like MapSignal can extend this trait, but more general types like
  * Signal[A] need to remain covariant in A for good ergonomics.
  */
trait WritableSignal[A] extends Signal[A] with WritableObservable[A] {

  protected var maybeLastSeenCurrentValue: UndefOr[Try[A]] = undefined

  protected def setCurrentValue(newValue: Try[A]): Unit = {
    val isInitial = maybeLastSeenCurrentValue.isEmpty
    // dom.console.log(s"> ${this} > setCurrentValue > ${newValue}, isInitial = ${isInitial}")
    if (!isInitial) {
      _lastUpdateId = runtime.nextUpdateId()
    }
    maybeLastSeenCurrentValue = newValue
  }

  // #Warning: be very careful when overriding tryNow. The returned value must
  //  conceptually match maybeLastSeenCurrentValue, as we can sometimes use
  //  the latter instead of calling tryNow, e.g. we do this in DistinctSignal
  //  to avoid infinite loop between tryNow and currentValueFromParent when
  //  mixing DistinctSignal with LazyStrictSignal in one class instance.
  /** Note: Initial value is only evaluated if/when needed (when there are observers) */
  override protected[airstream] def tryNow(): Try[A] = {
    // dom.console.log(s"> ${this} > tryNow (maybeLastSeenCurrentValue = ${maybeLastSeenCurrentValue})")
    maybeLastSeenCurrentValue.getOrElse {
      // #TODO[Integrity] I'm not sure if updating `_lastUpdateId` here is right.
      //  - I expected `0` to indicate "initial value" (no events emitted yet),
      //    as mentioned in Signal.nextUpdateId, but it seems that it's only `0`
      //    until the signal's initial value is evaluated, then we increment it here.
      //  - I'm not sure if this is done on purpose or not, it's possible that the
      //    comment is incorrect. Either way, need to figure this out some time.
      //  - Currently, tests pass either way, but it's hard to tell definitely
      //    if this change would really be ok.
      // dom.console.log(s"${this} -> eval current value from tryNow")
      _lastUpdateId = runtime.nextUpdateId()
      val nextValue = currentValueFromParent()
      setCurrentValue(nextValue)
      nextValue
    }
  }

  final override protected def fireValue(nextValue: A, transaction: Transaction): Unit = {
    fireTry(Success(nextValue), transaction)
  }

  final override protected def fireError(nextError: Throwable, transaction: Transaction): Unit = {
    fireTry(Failure(nextError), transaction)
  }

  override protected def fireTry(nextValue: Try[A], transaction: Transaction): Unit = {
    // dom.console.log(s"> $this > FIRE > $nextValue")

    setCurrentValue(nextValue)

    // === CAUTION ===
    // The following logic must match EventStream's fireValue / fireError! It is separated here for performance.

    // @TODO[API] It is rather curious/unintuitive that firing external observers first seems to make more sense. Think about it some more.

    isSafeToRemoveObserver = false

    // Explicit index loops, for the reason given in WritableStream.fireValue. An error counts as reported once any
    // observer received it, so the two loop counters say it without a flag the loops would have to capture.

    var external = 0
    while (external < externalObserverCount) {
      val observer = externalObserverAt(external)
      external += 1
      observer.onTry(nextValue)
    }

    var internal = 0
    while (internal < internalObserverCount) {
      val observer = internalObserverAt(internal)
      internal += 1
      InternalObserver.onTry(observer, nextValue, transaction)
    }

    isSafeToRemoveObserver = true

    maybePendingObserverRemovals.foreach { pendingObserverRemovals =>
      pendingObserverRemovals.forEach(remove => remove())
      pendingObserverRemovals.length = 0
    }

    // This will only ever happen for special Signals that maintain their current value even without observers.
    // Currently we only have one kind of such signal: StrictSignal.
    //
    // We want to report unhandled errors on such signals if they have no observers (including internal observers)
    // because if we don't, the error will not be reported anywhere, and I think we would usually want it to be reported.
    nextValue match {
      case Failure(error) if external == 0 && internal == 0 && runtime.strictSignalErrorReporting =>
        AirstreamError.sendUnhandledError(error)
      case _ => ()
    }
  }

}
