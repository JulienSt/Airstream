package com.raquo.airstream.split

import scala.scalajs.js

/** The [[MutableSplittable]] instance that only exists on Scala.js.
  *
  * A raw `js.Array` is a JavaScript type, so there is nothing on another platform for this instance to be about. It lives
  * in a trait the companion mixes in, which keeps it in the companion's implicit scope where the compiler looks for it.
  *
  * The growable-array instance is not here: it works unchanged on both platforms now that the array itself does.
  */
trait MutableSplittablePlatformInstances {

  implicit object ScalaJsArrayMutableSplittable extends MutableSplittable[js.Array] {

    override val splittable: Splittable[js.Array] = Splittable.ScalaJsArraySplittable

    override def isEmpty[A](items: js.Array[A]): Boolean = items.length == 0

    override def size[A](items: js.Array[A]): Int = items.length

    override def getByIndex[A](items: js.Array[A], index: Int): A = items(index)

    override def updateAtIndex[A](items: js.Array[A], index: Int, newItem: A): Unit = {
      items.update(index, newItem)
    }
  }
}
