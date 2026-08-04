package com.raquo.airstream.platform

import scala.collection.mutable

/** The keyed store the transaction bookkeeping is built on, for platforms without JavaScript.
  *
  * `Transaction` uses one to remember which child transactions belong to which parent. That set is rewritten constantly
  * while a graph fires, which is why it is a mutable map rather than an immutable one rebuilt on every change.
  *
  * `LinkedHashMap` rather than `HashMap`, and that is not a detail: nested transactions have to run in the order they were
  * created, and JavaScript's own `Map` iterates in insertion order. A hash-ordered map would fire a graph in a different
  * sequence than the JavaScript build does, and it would do so only once enough entries existed to make the hash order
  * diverge — which is to say, only under load and only sometimes.
  */
final class JsMap[K, V] {

    private val underlying: mutable.LinkedHashMap[K, V] = mutable.LinkedHashMap.empty

    /** How many entries the map holds. */
    def size: Int = underlying.size

    /** Look up the value stored under `key`.
      *
      * @return
      *   the value, or nothing when the key is absent
      */
    def get(key: K): UndefOr[V] = underlying.get(key).fold(undefined)(UndefOr.apply)

    /** Store `value` under `key`, replacing whatever was there. */
    def set(key: K, value: V): Unit = underlying.update(key, value)

    /** Remove the entry under `key`. Accepts a key that is not there, because a caller cannot always know. */
    def delete(key: K): Unit = underlying.remove(key)

    /** Visit every entry in insertion order.
      *
      * The value comes before the key, matching the JavaScript signature the call sites were written against. Reordering
      * the parameters here would read better in isolation and would silently break every existing call.
      */
    def forEach(visit: (V, K) => Unit): Unit = underlying.foreach { case (key, value) => visit(value, key) }
}
