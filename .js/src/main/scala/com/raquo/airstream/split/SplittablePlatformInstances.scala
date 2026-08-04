package com.raquo.airstream.split

import scala.scalajs.js

import com.raquo.ew.{ewArray, JsVector}

/** The [[Splittable]] instances that only exist on Scala.js.
  *
  * A raw `js.Array` and a `JsVector` are JavaScript types; there is nothing on another platform for these instances to be
  * about. They sit in a trait the companion mixes in so they stay in the companion's implicit scope, which is where the
  * compiler looks — an instance defined anywhere else would compile and never be found.
  *
  * The growable-array instance is deliberately not here. It moved into shared code once its only JavaScript-specific line,
  * the construction, went through the platform layer.
  */
trait SplittablePlatformInstances {

  implicit object JsVectorSplittable extends Splittable[JsVector] {

    override def create[A](values: Seq[A]): JsVector[A] = js.Array(values: _*).ew.unsafeAsJsVector // #Safe because we don't mutate the vector here

    override def map[A, B](inputs: JsVector[A], project: A => B): JsVector[B] = inputs.map(project)

    override def foreach[A](inputs: JsVector[A], f: A => Unit): Unit = inputs.forEach(f)

    override def isEmpty[A](inputs: JsVector[A]): Boolean = inputs.length == 0
  }

  implicit object ScalaJsArraySplittable extends Splittable[js.Array] {

    override def create[A](values: Seq[A]): js.Array[A] = js.Array(values: _*)

    override def map[A, B](inputs: js.Array[A], project: A => B): js.Array[B] = inputs.map(project)

    override def foreach[A](inputs: js.Array[A], f: A => Unit): Unit = inputs.foreach(f)

    override def isEmpty[A](inputs: js.Array[A]): Boolean = inputs.isEmpty
  }
}
