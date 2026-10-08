package com.raquo.airstream.platform

import scala.annotation.targetName

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

/** `A` is the lower bound, so a plain value is an `UndefOr[A]` by subtyping, exactly as `A <: js.UndefOr[A]` on Scala.js:
  * assigning a value to a slot needs no conversion and allocates nothing, and outside this file the representation stays
  * hidden, because only the bound is visible there, not the union.
  */
opaque type UndefOr[+A] >: A = A | UndefinedValue.type

/** The absent value, reachable without naming the companion so shared code reads identically on both platforms — on
  * Scala.js this same name resolves to JavaScript's `undefined`.
  */
val undefined: UndefOr[Nothing] = UndefinedValue

/** The empty value, the explicit lift, and the combinators. */
object UndefOr {

    /** The absent value. */
    val undefined: UndefOr[Nothing] = UndefinedValue

    /** Lift `value` into the type.
      *
      * @return
      *   the same reference, now typed as possibly-absent
      */
    def apply[A](value: A): UndefOr[A] = value

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
        @targetName("inlineIsEmpty")
        inline def isEmpty: Boolean = isUndefined

        /** True when a value is there. */
        @targetName("inlineIsDefined")
        inline def isDefined: Boolean = !isUndefined

        /** True when a value is there. The other spelling, because both appear at the call sites. */
        @targetName("inlineNonEmpty")
        inline def nonEmpty: Boolean = !isUndefined

        /** True when the value there equals `expected`. False when nothing is there. */
        @targetName("inlineContains")
        inline def contains[B >: A](expected: B): Boolean = !isUndefined && presentValue == expected

        /** True when `predicate` holds for the value, and vacuously true when nothing is there — matching Option. */
        @targetName("inlineForall")
        inline def forall(inline predicate: A => Boolean): Boolean = isUndefined || predicate(presentValue)

        /** True when a present value satisfies `predicate`; false when nothing is there. */
        @targetName("inlineExists")
        inline def exists(inline predicate: A => Boolean): Boolean = !isUndefined && predicate(presentValue)

        /** Read the value, falling back to `default` when nothing is there.
          *
          * `default` is evaluated only when nothing is there, which matters at the call site in Transaction where the
          * fallback builds a new priority queue.
          *
          * @return
          *   the value, or the fallback
          */
        @targetName("inlineGetOrElse")
        inline def getOrElse[B >: A](inline default: B): B =
            if(isUndefined) { default }
            else { presentValue }

        /** Apply `transform` to a value that is there, leaving an absent one absent.
          *
          * @return
          *   the transformed value, or nothing
          */
        @targetName("inlineMap")
        inline def map[B](inline transform: A => B): UndefOr[B] =
            if(isUndefined) { UndefinedValue }
            else { transform(presentValue) }

        /** Keep a present value only when `predicate` accepts it. */
        @targetName("inlineFilter")
        inline def filter(inline predicate: A => Boolean): UndefOr[A] =
            if(!isUndefined && predicate(presentValue)) { self }
            else { UndefinedValue }

        /** Run `body` for a value that is there, and do nothing at all otherwise. */
        @targetName("inlineForeach")
        inline def foreach(inline body: A => Unit): Unit = if(!isUndefined) { body(presentValue) }

        /** Collapse to a single value: `transform` when something is there, `ifEmpty` when nothing is.
          *
          * @return
          *   the folded result
          */
        @targetName("inlineFold")
        inline def fold[B](inline ifEmpty: B)(inline transform: A => B): B =
            if(isUndefined) { ifEmpty }
            else { transform(presentValue) }

        /** Read the value, assuming one is there.
          *
          * @return
          *   the value
          */
        @targetName("inlineGet")
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

    // Consumers compiled before these combinators became inline call ordinary methods on this object. Keep those binary
    // names while new source calls the inline extensions above. Each adapter delegates to the same implementation;
    // by-name arguments preserve the old lazy evaluation, and the opaque representation is unchanged. These are not
    // extensions or givens, so no hot call site resolves to an adapter or reintroduces an implicit conversion.
    @targetName("isEmpty")
    private[platform] def binaryIsEmpty[A](self: UndefOr[A]): Boolean = self.isEmpty

    @targetName("isDefined")
    private[platform] def binaryIsDefined[A](self: UndefOr[A]): Boolean = self.isDefined

    @targetName("nonEmpty")
    private[platform] def binaryNonEmpty[A](self: UndefOr[A]): Boolean = self.nonEmpty

    @targetName("contains")
    private[platform] def binaryContains[A, B >: A](self: UndefOr[A])(expected: B): Boolean = self.contains(expected)

    @targetName("forall")
    private[platform] def binaryForall[A](self: UndefOr[A])(predicate: A => Boolean): Boolean = self.forall(predicate)

    @targetName("exists")
    private[platform] def binaryExists[A](self: UndefOr[A])(predicate: A => Boolean): Boolean = self.exists(predicate)

    @targetName("getOrElse")
    private[platform] def binaryGetOrElse[A, B >: A](self: UndefOr[A])(default: => B): B = self.getOrElse(default)

    @targetName("map")
    private[platform] def binaryMap[A, B](self: UndefOr[A])(transform: A => B): UndefOr[B] = self.map(transform)

    @targetName("filter")
    private[platform] def binaryFilter[A](self: UndefOr[A])(predicate: A => Boolean): UndefOr[A] = self.filter(predicate)

    @targetName("foreach")
    private[platform] def binaryForeach[A](self: UndefOr[A])(body: A => Unit): Unit = self.foreach(body)

    @targetName("fold")
    private[platform] def binaryFold[A, B](self: UndefOr[A])(ifEmpty: => B)(transform: A => B): B = self.fold(ifEmpty)(transform)

    @targetName("get")
    private[platform] def binaryGet[A](self: UndefOr[A]): A = self.get

    @targetName("liftToUndefOr")
    private[platform] def binaryLiftToUndefOr[A]: Conversion[A, UndefOr[A]] = value => value
}
