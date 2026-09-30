package com.raquo.airstream

/** Scala 3 keeps the original higher-kinded aliases so emitted generic signatures match pre-port Airstream. */
package object platform {

    type JsArray = [A] =>> com.raquo.ew.JsArray[A]

    val JsArray: com.raquo.ew.JsArray.type = com.raquo.ew.JsArray

    type JsMap = [K, V] =>> com.raquo.ew.JsMap[K, V]

    type JsSet = [A] =>> com.raquo.ew.JsSet[A]

    val JsSet: com.raquo.ew.JsSet.type = com.raquo.ew.JsSet

    type JsVector = [A] =>> com.raquo.ew.JsVector[A]

    type ScalaJsArray = [A] =>> scala.scalajs.js.Array[A]

    type JsCallback[-A] = scala.scalajs.js.Function1[A, Unit]

    type UndefOr[+A] = scala.scalajs.js.UndefOr[A]

    @inline def undefined: UndefOr[Nothing] = scala.scalajs.js.undefined
}
