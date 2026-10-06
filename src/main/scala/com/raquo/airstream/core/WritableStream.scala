package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.ObserverError

import scala.util.Try

trait WritableStream[A] extends EventStream[A] with WritableObservable[A] {

  override protected def fireValue(nextValue: A, transaction: Transaction): Unit = {
    // dom.console.log(s"> $this > FIRE > $nextValue")

    // === CAUTION ===
    // The following logic must match Signal's fireTry! It is separated here for performance.

    isSafeToRemoveObserver = false

    // The observer lists are walked with explicit index loops, here and in fireError and Signal's fireTry, rather than
    // with `ObserverList.foreach`: a closure over the value and the transaction would be an object per node per event
    // (`FireAllocationSpec`). As in `foreach`, the index moves on before the observer runs, and the length is
    // read on every step, so an observer added during the loop is notified too.

    var external = 0
    while (external < externalObservers.length) {
      val observer = externalObservers(external)
      external += 1
      try {
        observer.onNext(nextValue)
      } catch {
        case err: Throwable => AirstreamError.sendUnhandledError(ObserverError(err))
      }
    }

    var internal = 0
    while (internal < internalObservers.length) {
      val observer = internalObservers(internal)
      internal += 1
      InternalObserver.onNext(observer, nextValue, transaction)
    }

    isSafeToRemoveObserver = true

    maybePendingObserverRemovals.foreach { pendingObserverRemovals =>
      pendingObserverRemovals.forEach(remove => remove())
      pendingObserverRemovals.length = 0
    }
  }

  override protected def fireError(nextError: Throwable, transaction: Transaction): Unit = {
    // dom.console.log(s"> $this > FIRE > $nextError")

    // === CAUTION ===
    // The following logic must match Signal's fireTry! It is separated here for performance.

    isSafeToRemoveObserver = false

    var external = 0
    while (external < externalObservers.length) {
      val observer = externalObservers(external)
      external += 1
      observer.onError(nextError)
    }

    var internal = 0
    while (internal < internalObservers.length) {
      val observer = internalObservers(internal)
      internal += 1
      InternalObserver.onError(observer, nextError, transaction)
    }

    isSafeToRemoveObserver = true

    maybePendingObserverRemovals.foreach { pendingObserverRemovals =>
      pendingObserverRemovals.forEach(remove => remove())
      pendingObserverRemovals.length = 0
    }
  }

  final override protected def fireTry(nextValue: Try[A], transaction: Transaction): Unit = {
    nextValue.fold(
      fireError(_, transaction),
      fireValue(_, transaction)
    )
  }

}
