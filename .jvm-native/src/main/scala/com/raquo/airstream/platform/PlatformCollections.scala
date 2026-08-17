package com.raquo.airstream.platform

object PlatformCollections {

  def jsVector[A](values: Seq[A]): JsVector[A] = JsVector(values: _*)

  def scalaJsArray[A](values: Seq[A]): ScalaJsArray[A] = ScalaJsArray(values: _*)
}
