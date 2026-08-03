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

    /** How many elements the array currently holds. */
    def length: Int = underlying.length

    /** Read the element at `index`.
      *
      * Reading out of bounds raises here, whereas a JavaScript array would answer `undefined`. That divergence is
      * deliberate and is the reason it is written down: Airstream guards its own indexed reads, and a silent `undefined`
      * flowing into the reactive graph would surface far from its cause. An exception names the spot.
      *
      * @return
      *   the element standing at that index
      */
    def apply(index: Int): A = underlying(index)

    /** Replace the element at `index`, leaving the length alone. */
    def update(index: Int, value: A): Unit = underlying.update(index, value)

    /** Append `value` at the end. */
    def push(value: A): Unit = underlying.append(value)

    /** Remove and return the first element.
      *
      * @return
      *   the element that was at the front
      */
    def shift(): A = underlying.remove(0)

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

    /** Visit every element in order. Spelled the JavaScript way, for the call sites that were written against it. */
    def forEach(visit: A => Unit): Unit = underlying.foreach(visit)

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
