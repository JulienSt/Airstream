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
  * Scala 3 only, and that is why the Native build is pinned to it. Opaque types and extension methods are what make this
  * free at runtime; the Scala 2.13 build stays on the JavaScript side where the platform provides the type natively.
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

    extension [A](self: UndefOr[A]) {

        private inline def isUndefined: Boolean = self.asInstanceOf[AnyRef] eq UndefinedValue

        /** True when nothing is there. */
        def isEmpty: Boolean = isUndefined

        /** True when a value is there. */
        def isDefined: Boolean = !isUndefined

        /** True when a value is there. The other spelling, because both appear at the call sites. */
        def nonEmpty: Boolean = !isUndefined

        /** True when the value there equals `expected`. False when nothing is there. */
        def contains[B >: A](expected: B): Boolean = !isUndefined && self.asInstanceOf[A] == expected

        /** True when `predicate` holds for the value, and vacuously true when nothing is there — matching Option. */
        def forall(predicate: A => Boolean): Boolean = isUndefined || predicate(self.asInstanceOf[A])

        /** True when a present value satisfies `predicate`; false when nothing is there. */
        def exists(predicate: A => Boolean): Boolean = !isUndefined && predicate(self.asInstanceOf[A])

        /** Read the value, falling back to `default` when nothing is there.
          *
          * `default` is by-name so it is not evaluated when a value is present, which matters at the call site in
          * Transaction where the fallback builds a new priority queue.
          *
          * @return
          *   the value, or the fallback
          */
        def getOrElse[B >: A](default: => B): B =
            if(isUndefined) { default }
            else { self.asInstanceOf[A] }

        /** Apply `transform` to a value that is there, leaving an absent one absent.
          *
          * @return
          *   the transformed value, or nothing
          */
        def map[B](transform: A => B): UndefOr[B] =
            if(isUndefined) { UndefinedValue }
            else { transform(self.asInstanceOf[A]) }

        /** Keep a present value only when `predicate` accepts it. */
        def filter(predicate: A => Boolean): UndefOr[A] =
            if(!isUndefined && predicate(self.asInstanceOf[A])) { self }
            else { UndefinedValue }

        /** Run `body` for a value that is there, and do nothing at all otherwise. */
        def foreach(body: A => Unit): Unit = if(!isUndefined) { body(self.asInstanceOf[A]) }

        /** Collapse to a single value: `transform` when something is there, `ifEmpty` when nothing is.
          *
          * @return
          *   the folded result
          */
        def fold[B](ifEmpty: => B)(transform: A => B): B =
            if(isUndefined) { ifEmpty }
            else { transform(self.asInstanceOf[A]) }

        /** Read the value, assuming one is there.
          *
          * @return
          *   the value
          */
        def get: A = self.asInstanceOf[A]

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
            else { Some(self.asInstanceOf[A]) }
    }
}
