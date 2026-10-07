package com.raquo.airstream.platform

object PlatformCollections {

  /** An empty result array whose final size is known by the caller. */
  private[airstream] def jsArrayWithCapacity[A](capacity: Int): JsArray[A] = JsArray.withCapacity(capacity)

  def jsVector[A](values: Seq[A]): JsVector[A] = JsVector(values: _*)

  def scalaJsArray[A](values: Seq[A]): ScalaJsArray[A] = ScalaJsArray(values: _*)
}
