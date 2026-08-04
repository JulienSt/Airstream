package com.raquo.airstream.platform

import scala.collection.mutable

/** The raw JavaScript-array shape retained by Airstream's public splitting API on Native. */
final class ScalaJsArray[A] private (private val underlying: mutable.ArrayBuffer[A]) {

  def length: Int = underlying.length

  def apply(index: Int): A = underlying(index)

  def update(index: Int, value: A): Unit = underlying.update(index, value)

  def map[B](project: A => B): ScalaJsArray[B] = new ScalaJsArray(underlying.map(project))

  def foreach(visit: A => Unit): Unit = underlying.foreach(visit)

  def isEmpty: Boolean = underlying.isEmpty

  def toSeq: Seq[A] = underlying.toSeq
}

object ScalaJsArray {

  def apply[A](values: A*): ScalaJsArray[A] = new ScalaJsArray(mutable.ArrayBuffer.from(values))
}
