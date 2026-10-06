package com.raquo.airstream.platform

/** The allocation-free optional value Airstream's core is built on, for platforms without JavaScript.
  *
  * Airstream deliberately does not use `Option` in its hot paths. A pending-observable slot, a parent transaction, a
  * cached value: each is read on every event, and wrapping each one in a `Some` would allocate on every event. This opaque
  * union stores a present value as that value itself and uses one process-wide singleton as the absent sentinel. Lifting,
  * reading, mapping, and defaulting allocate no wrapper.
  *
  * A dedicated sentinel rather than `null` matters. `js.UndefOr` distinguishes a present `null` from `undefined`, and
  * generic Scala APIs can legitimately carry null even though Airstream's current hot slots do not. Using null as the
  * sentinel would therefore make the two platforms disagree at exactly that boundary.
  *
  * A union with a singleton reference boxes primitive values. JavaScript has no equivalent cost, but Airstream's hot
  * optional slots hold transactions, observables, and queues rather than primitives. Reference values retain their exact
  * object identity and receive no wrapper.
  *
  * Scala 3 only, and that is why both non-JavaScript builds are pinned to it. Opaque types and extension methods make this
  * free at runtime; the Scala 2.13 build stays on JavaScript where the platform provides the type natively.
  */
private object UndefinedValue

opaque type UndefOr[+A] = A | UndefinedValue.type

/** The absent value, reachable without naming the companion so shared code reads identically on both platforms — on
  * Scala.js this same name resolves to JavaScript's `undefined`.
  */
val undefined: UndefOr[Nothing] = UndefinedValue

/** The empty value, and the lift that lets a plain value be assigned to a slot of this type. */
object UndefOr {

    /** The absent value. */
    val undefined: UndefOr[Nothing] = UndefinedValue

    /** Lift `value` into the type.
      *
      * @return
      *   the same reference, now typed as possibly-absent
      */
    def apply[A](value: A): UndefOr[A] = value

    /** Assigning a plain value to a slot of this type is how Airstream fills the transaction's pending-observable slot, so
      * the conversion has to be available implicitly for the shared code to read the way it always did.
      */
    given liftToUndefOr[A]: Conversion[A, UndefOr[A]] = value => value

    /** The combinators are `inline` with `inline` function arguments, so a call site's lambda is beta-reduced into it and no
      * function object exists at run time. The hot paths call them with lambdas that capture the engine, the transaction or
      * the signal; passed to an ordinary method, each would be an object per call that only the JIT's escape analysis
      * removes, and only while the call site stays monomorphic (`UndefOrAllocationSpec`).
      */
    extension [A](self: UndefOr[A]) {

        /** A type test against the sentinel's singleton type, which compiles to a reference comparison. */
        private inline def isUndefined: Boolean = self.isInstanceOf[UndefinedValue.type]

        /** The present value. The one narrowing of the union to `A`: erased, it emits no instruction, and it is only reached
          * after `isUndefined` ruled the sentinel out.
          */
        private inline def presentValue: A = self.asInstanceOf[A]

        /** True when nothing is there. */
        inline def isEmpty: Boolean = isUndefined

        /** True when a value is there. */
        inline def isDefined: Boolean = !isUndefined

        /** True when a value is there. The other spelling, because both appear at the call sites. */
        inline def nonEmpty: Boolean = !isUndefined

        /** True when the value there equals `expected`. False when nothing is there. */
        inline def contains[B >: A](expected: B): Boolean = !isUndefined && presentValue == expected

        /** True when `predicate` holds for the value, and vacuously true when nothing is there — matching Option. */
        inline def forall(inline predicate: A => Boolean): Boolean = isUndefined || predicate(presentValue)

        /** True when a present value satisfies `predicate`; false when nothing is there. */
        inline def exists(inline predicate: A => Boolean): Boolean = !isUndefined && predicate(presentValue)

        /** Read the value, falling back to `default` when nothing is there.
          *
          * `default` is evaluated only when nothing is there, which matters at the call site in Transaction where the
          * fallback builds a new priority queue.
          *
          * @return
          *   the value, or the fallback
          */
        inline def getOrElse[B >: A](inline default: B): B =
            if(isUndefined) { default }
            else { presentValue }

        /** Apply `transform` to a value that is there, leaving an absent one absent.
          *
          * @return
          *   the transformed value, or nothing
          */
        inline def map[B](inline transform: A => B): UndefOr[B] =
            if(isUndefined) { UndefinedValue }
            else { transform(presentValue) }

        /** Keep a present value only when `predicate` accepts it. */
        inline def filter(inline predicate: A => Boolean): UndefOr[A] =
            if(!isUndefined && predicate(presentValue)) { self }
            else { UndefinedValue }

        /** Run `body` for a value that is there, and do nothing at all otherwise. */
        inline def foreach(inline body: A => Unit): Unit = if(!isUndefined) { body(presentValue) }

        /** Collapse to a single value: `transform` when something is there, `ifEmpty` when nothing is.
          *
          * @return
          *   the folded result
          */
        inline def fold[B](inline ifEmpty: B)(inline transform: A => B): B =
            if(isUndefined) { ifEmpty }
            else { transform(presentValue) }

        /** Read the value, assuming one is there.
          *
          * @return
          *   the value
          */
        inline def get: A = presentValue

        /** Convert to the standard optional type, allocating in the process.
          *
          * Present for the boundaries where a value leaves the hot path and meets ordinary Scala code. This deliberately
          * uses `Some` rather than `Option` so a present null remains distinguishable from the absent sentinel.
          *
          * @return
          *   the value as an Option
          */
        def toOption: Option[A] =
            if(isUndefined) { None }
            else { Some(presentValue) }
    }
}
