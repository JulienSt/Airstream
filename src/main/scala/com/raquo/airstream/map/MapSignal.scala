package com.raquo.airstream.map

import com.raquo.airstream.common.SingleParentSignal
import com.raquo.airstream.core.{Protected, Signal, Transaction}
import com.raquo.airstream.core.AirstreamError.{ErrorHandlingError, InitialValueError}

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

// @TODO[Elegance] Is this right that we shoved .recover into Map observables? Maybe it deserves its own class? But it's so many classes...

/** This signal emits an error if the parent observable emits an error or if `project` throws
  *
  * If `recover` is defined and needs to be called, it can do the following:
  *  - Return Some(value) to make this signal emit value
  *  - Return None to make this signal ignore (swallow) this error
  *    - #Warning: Except if this error is the Signal's initial value, then we'll use it anyway.
  *  - Not handle the error (meaning .isDefinedAt(error) must be false) to emit the original error
  *
  * @param project Note: guarded against exceptions
  * @param recover Note: guarded against exceptions
  */
class MapSignal[I, O](
  protected val parent: Signal[I],
  protected val project: I => O,
  protected val recover: Option[PartialFunction[Throwable, Option[O]]]
) extends SingleParentSignal[I, O] {

  override protected val topoRank: Int = Protected.topoRank(parent) + 1

  // Matches rather than `Try.fold` and `Option.fold`: a fold builds a closure for each branch before it picks one, and this
  // runs once per node per event.
  override protected def onTry(nextParentValue: Try[I], transaction: Transaction): Unit = {
    super.onTry(nextParentValue, transaction)
    nextParentValue match {
      case Success(parentValue) =>
        fireTry(projected(parentValue), transaction)
      case Failure(nextError) =>
        recover match {
          case None =>
            // if no `recover` specified, fire original error
            fireError(nextError, transaction)
          case Some(pf) =>
            Try(pf.lift(nextError)) match {
              case Failure(pfError) =>
                // if recover throws error, fire wrapped error
                fireError(ErrorHandlingError(error = pfError, cause = nextError), transaction)
              case Success(None) =>
                // If recover was not applicable, fire original error
                fireError(nextError, transaction)
              case Success(Some(Some(nextValue))) =>
                // If recover was applicable and resulted in a new value, fire that value
                fireValue(nextValue, transaction)
              case Success(Some(None)) =>
                // If recover was applicable and resulted in None, swallow the error
                ()
            }
        }
    }
  }

  /** `project` applied to the parent's value, its non-fatal exception caught exactly as `Try.map` catches it, written out
    * because `Try.map` hands `Try.apply` a closure over the function and the value on every event.
    */
  private def projected(parentValue: I): Try[O] = {
    try {
      Success(project(parentValue))
    } catch {
      case NonFatal(err) => Failure(err)
    }
  }

  override protected def currentValueFromParent(): Try[O] = {
    val originalValue = parent.tryNow().map(project)

    originalValue.fold(
      // if no `recover` specified, keep original error
      nextError =>
        recover.fold(
          // If no `recover` specified, use original error
          originalValue
        ) { pf =>
          Try(pf.lift(nextError)).fold(
            pfError => {
              // if recover throws error, save wrapped error
              Failure(ErrorHandlingError(error = pfError, cause = nextError))
            },
            _.fold({
              // If recover was not applicable, keep original value
              originalValue
            }) { nextValueOpt =>
              // @TODO[Test] Verify this
              // If recover was applicable and resulted in Some(value), use that.
              // If None, we want to keep the signal's previous value (maybeLastSeenCurrentValue),
              // but if that's not available (because we're currently evaluating Signal's initial value),
              // then we're forced to use the original error because we can't "skip" initial value.
              // #TODO[Integrity,API] – this is like having `filter` on signals, but in the error channel
              nextValueOpt.fold(
                maybeLastSeenCurrentValue.getOrElse(Failure(InitialValueError(error = nextError)))
              )(
                Success(_)
              )
            }
          )
        },
      _ => originalValue
    )
  }
}
