package com.raquo.airstream

/** The Scala.js side of the platform layer.
  *
  * On this platform the growable array Airstream's core is built on is a JavaScript array, exactly as it always was. The
  * alias costs nothing at runtime: no wrapper is allocated, no method is forwarded, and the emitted code is identical to
  * what naming `com.raquo.ew.JsArray` directly produced. That is the point — introducing a platform layer must not make
  * the platform that already worked any slower.
  */
package object platform {

    type JsArray[A] = com.raquo.ew.JsArray[A]

    val JsArray: com.raquo.ew.JsArray.type = com.raquo.ew.JsArray

    /** The keyed store the transaction bookkeeping uses. JavaScript's own `Map` already iterates in insertion order,
      * which is what nested transactions depend on.
      */
    type JsMap[K, V] = com.raquo.ew.JsMap[K, V]

    /** The immutable JavaScript vector retained by the splitting API. */
    type JsVector[A] = com.raquo.ew.JsVector[A]

    /** A raw JavaScript array retained by the splitting API. */
    type ScalaJsArray[A] = scala.scalajs.js.Array[A]

    /** A callback callable by JavaScript code. */
    type JsCallback[-A] = scala.scalajs.js.Function1[A, Unit]

    /** The allocation-free optional value. On this platform it is JavaScript's own, which is what Airstream has always
      * used; the alias adds no wrapper and no forwarding.
      */
    type UndefOr[+A] = scala.scalajs.js.UndefOr[A]

    /** The empty value. */
    @inline def undefined: UndefOr[Nothing] = scala.scalajs.js.undefined
}
