package com.raquo.airstream.platform

import scala.collection.mutable

/** The set duplicate detection is built on, for platforms without JavaScript.
  *
  * `hasDuplicateKeys` guards `Var.set` and `WriteBus.emit` against listing the same Var or bus twice in one transaction. It
  * relies on JavaScript's `Set` semantics (SameValueZero): objects compare by reference, while strings, numbers and booleans
  * compare by value. A Scala `HashSet` would use `equals` instead and call two distinct but equal-looking Vars one Var, so this
  * set normalises every key the way JavaScript would before it stores it.
  *
  * Numbers follow JavaScript, where every number is a double: `1` and `1.0` are the same key, every `NaN` is the same key, and
  * `0.0` equals `-0.0`. `Long` and `Char` are objects in Scala.js, so here too they compare by reference.
  */
final class JsSet[A] {

  private val underlying: mutable.LinkedHashSet[Any] = mutable.LinkedHashSet.empty

  /** How many distinct keys the set holds. */
  def size: Int = underlying.size

  /** Add `value` unless an equal key under JavaScript semantics is already present.
    *
    * @return
    *   this set, matching the chaining JavaScript's `Set.add` offers
    */
  def add(value: A): JsSet[A] = {
    underlying.add(JsSet.key(value))
    this
  }

  /** Tell whether a key equal to `value` under JavaScript semantics is present. */
  def has(value: A): Boolean = underlying.contains(JsSet.key(value))
}

object JsSet {

  /** A new empty set. */
  def empty[A]: JsSet[A] = new JsSet[A]

  /** A key compared by the identity of the object it wraps, like a JavaScript object in a `Set`. */
  final private class Reference(val target: AnyRef) {
    override def equals(other: Any): Boolean = other match {
      case reference: Reference => reference.target eq target
      case _                    => false
    }
    override def hashCode: Int = System.identityHashCode(target)
  }

  /** A number key: every JavaScript number is a double, with one `NaN` and no negative zero. The key holds the double's bits,
    * because comparing the doubles themselves would make `NaN` unequal to itself.
    */
  final private case class Number(bits: Long)

  /** The key of `null`, which JavaScript compares by value. */
  private case object Null

  /** Normalise one value into the key JavaScript's `Set` would compare it by. */
  private def key(value: Any): Any = value match {
    case null                   => Null
    case text: String           => text
    case flag: java.lang.Boolean => flag
    case number: java.lang.Integer => normalise(number.doubleValue)
    case number: java.lang.Short   => normalise(number.doubleValue)
    case number: java.lang.Byte    => normalise(number.doubleValue)
    case number: java.lang.Float   => normalise(number.doubleValue)
    case number: java.lang.Double  => normalise(number.doubleValue)
    case other: AnyRef          => new Reference(other)
  }

  private def normalise(number: Double): Number = Number(java.lang.Double.doubleToLongBits(number + 0.0))
}
