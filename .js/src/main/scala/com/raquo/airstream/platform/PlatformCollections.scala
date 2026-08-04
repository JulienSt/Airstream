package com.raquo.airstream.platform

import com.raquo.ew.ewArray

import scala.scalajs.js

object PlatformCollections {

  @inline def jsVector[A](values: Seq[A]): JsVector[A] =
    js.Array(values: _*).ew.unsafeAsJsVector

  @inline def scalaJsArray[A](values: Seq[A]): ScalaJsArray[A] =
    js.Array(values: _*)
}
