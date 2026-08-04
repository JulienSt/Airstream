package com.raquo.airstream.platform

/** The allocation-free optional value Airstream's core is built on, for platforms without JavaScript.
  *
  * Airstream deliberately does not use `Option` in its hot paths. A pending-observable slot, a parent transaction, a
  * cached value: each is read on every event, and wrapping each one in a `Some` would allocate on every event. That
  * decision is worth preserving on a platform that has no `undefined`, so this is an opaque type over `A | Null` rather
  * than an `Option` behind a different name — at runtime a present value is the value itself, and an absent one is a null
  * reference. Nothing is allocated either way.
  *
  * The catch is the one every null-based optional has: a genuinely null value cannot be told apart from an absent one.
  * Airstream never stores one — every slot holds a transaction, an observable or a queue — so the trade is sound here and
  * would not be in general.
  *
  * A second caveat worth knowing before reaching for this elsewhere: a union with `Null` needs a reference, so holding a
  * primitive here boxes it. JavaScript has no such cost, which makes this the one place the two platforms genuinely
  * differ in what they spend. It does not bite in Airstream, whose slots all hold references, and it would bite anyone who
  * used this for an optional Int in a tight loop.
  *
  * Scala 3 only, and that is why the Native build is pinned to it. Opaque types and extension methods are what make this
  * free at runtime; the Scala 2.13 build stays on the JavaScript side where the platform provides the type natively.
  */
opaque type UndefOr[+A] = A | Null

/** The absent value, reachable without naming the companion so shared code reads identically on both platforms — on
  * Scala.js this same name resolves to JavaScript's `undefined`.
  */
val undefined: UndefOr[Nothing] = null

/** The empty value, and the lift that lets a plain value be assigned to a slot of this type. */
object UndefOr {

    /** The absent value. */
    val undefined: UndefOr[Nothing] = null

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
}

extension [A](self: UndefOr[A]) {

    /** True when nothing is there. */
    def isEmpty: Boolean = self == null

    /** True when a value is there. */
    def isDefined: Boolean = self != null

    /** Read the value, falling back to `default` when nothing is there.
      *
      * `default` is by-name so it is not evaluated when a value is present, which matters at the call site in Transaction
      * where the fallback builds a new priority queue.
      *
      * @return
      *   the value, or the fallback
      */
    def getOrElse[B >: A](default: => B): B = if(self == null) { default } else { self.asInstanceOf[A] }

    /** Apply `transform` to a value that is there, leaving an absent one absent.
      *
      * @return
      *   the transformed value, or nothing
      */
    def map[B](transform: A => B): UndefOr[B] = if(self == null) { null } else { transform(self.asInstanceOf[A]) }

    /** Run `body` for a value that is there, and do nothing at all otherwise. */
    def foreach(body: A => Unit): Unit = if(self != null) { body(self.asInstanceOf[A]) }

    /** Collapse to a single value: `transform` when something is there, `ifEmpty` when nothing is.
      *
      * @return
      *   the folded result
      */
    def fold[B](ifEmpty: => B)(transform: A => B): B =
        if(self == null) { ifEmpty }
        else { transform(self.asInstanceOf[A]) }

    /** Read the value, assuming one is there.
      *
      * @return
      *   the value
      */
    def get: A = self.asInstanceOf[A]

    /** Convert to the standard optional type, allocating in the process.
      *
      * Present for the boundaries where a value leaves the hot path and meets ordinary Scala code.
      *
      * @return
      *   the value as an Option
      */
    def toOption: Option[A] = Option(self.asInstanceOf[A])
}
