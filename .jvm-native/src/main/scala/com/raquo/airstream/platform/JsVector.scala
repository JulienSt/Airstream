package com.raquo.airstream.platform

/** The immutable JavaScript-vector shape retained by Airstream's public splitting API on Native. */
final class JsVector[A] private (private val underlying: Vector[A]) {

  def length: Int = underlying.length

  def map[B](project: A => B): JsVector[B] = new JsVector(underlying.map(project))

  def forEach(visit: A => Unit): Unit = underlying.foreach(visit)

  def isEmpty: Boolean = underlying.isEmpty

  def toSeq: Seq[A] = underlying
}

object JsVector {

  def apply[A](values: A*): JsVector[A] = new JsVector(values.toVector)

  def empty[A]: JsVector[A] = new JsVector(Vector.empty)
}
