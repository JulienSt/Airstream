package com.raquo.airstream.platform

import scala.collection.mutable

/** The growable array Airstream's core is built on, for platforms without JavaScript.
  *
  * Airstream reaches for a mutable array in its hot paths — the observer lists, the transaction queue, the pending-child
  * bookkeeping — because those are rewritten on every event and an immutable structure would allocate on each one. That
  * decision is worth preserving on a platform that has no `js.Array`, so this is a real mutable structure rather than a
  * persistent collection behind a mutable-looking name.
  *
  * `ArrayBuffer` underneath is deliberate: amortised constant-time append and constant-time indexed access are the two
  * operations that dominate, and it is the standard library's answer for exactly that shape.
  *
  * The surface is only what Airstream uses. Copying a foreign API in full would leave a second API to keep correct, and the
  * shared spec that runs against both platforms would have to cover operations nobody calls.
  */
final class JsArray[A] private (private val underlying: mutable.ArrayBuffer[A]) {

  @inline private def emptySlot: A = undefined.asInstanceOf[A]

  /** How many elements the array currently holds. */
  def length: Int = underlying.length

  /** Truncate to `newLength` elements, or pad nothing when it is already shorter.
    *
    * Assignable because the call sites clear a buffer by writing `length = 0`, which is how a JavaScript array is
    * emptied. Renaming it to `clear` would read better and would change every one of those call sites for no gain.
    */
  def length_=(newLength: Int): Unit = {
    val target = math.max(0, newLength)
    if (target < underlying.length) { underlying.remove(target, underlying.length - target) }
  }

  /** Read the element at `index`.
    *
    * JavaScript arrays answer `undefined` outside their bounds. Returning the platform's empty sentinel preserves that
    * contract for guarded reads such as the priority queue without allocating or changing the shared signature.
    *
    * @return
    *   the element standing at that index, or the empty sentinel when the index is outside the array
    */
  def apply(index: Int): A =
    if (index >= 0 && index < underlying.length) { underlying(index) }
    else { emptySlot }

  /** Replace the element at `index`, leaving the length alone. */
  def update(index: Int, value: A): Unit = underlying.update(index, value)

  /** Append `value` at the end. */
  def push(value: A): Unit = underlying.append(value)

  /** Insert `value` at the front, moving everything else along. */
  def unshift(value: A): Unit = underlying.prepend(value)

  /** Remove and return the first element.
    *
    * @return
    *   the element that was at the front, or the empty sentinel when the array was empty
    */
  def shift(): A =
    if (underlying.isEmpty) { emptySlot }
    else { underlying.remove(0) }

  /** Remove `deleteCount` elements starting at `index`, then insert `values` there.
    *
    * Named after the JavaScript operation it stands in for, because the call sites reading it were written against that
    * name and renaming it here would make the shared code read differently per platform.
    */
  def splice(index: Int, deleteCount: Int, values: A*): Unit = {
    underlying.remove(index, math.max(0, deleteCount))
    underlying.insertAll(index, values)
  }

  /** Find the first index holding `value`.
    *
    * @return
    *   that index, or -1 when the element is absent
    */
  def indexOf(value: A): Int = underlying.indexOf(value)

  /** Build a new array by applying `transform` to every element, leaving this one untouched.
    *
    * @return
    *   a new array holding the results
    */
  def map[B](transform: A => B): JsArray[B] = new JsArray(underlying.map(transform))

  /** Build a new array while exposing each source position to `transform`. */
  def mapWithIndex[B](transform: (A, Int) => B): JsArray[B] = {
    val result = new mutable.ArrayBuffer[B](underlying.length)
    var index = 0
    while (index < underlying.length) {
      result.append(transform(underlying(index), index))
      index += 1
    }
    new JsArray(result)
  }

  /** Visit every element in order. Spelled the JavaScript way, for the call sites that were written against it. */
  def forEach(visit: A => Unit): Unit = underlying.foreach(visit)

  /** Render elements exactly as a JavaScript array does: comma-separated, without collection wrappers. */
  override def toString: String = underlying.mkString(",")

  /** Visit every element with its position, in order. */
  def forEachWithIndex(visit: (A, Int) => Unit): Unit = {
    var index = 0
    while (index < underlying.length) {
      visit(underlying(index), index)
      index += 1
    }
  }

}

/** Ways of getting an array without naming the constructor. */
object JsArray {

  /** Build an array holding `values`, in the order given.
    *
    * @return
    *   a new array
    */
  def apply[A](values: A*): JsArray[A] = new JsArray(mutable.ArrayBuffer.from(values))
}
