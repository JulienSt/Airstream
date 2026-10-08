package com.raquo.airstream.core

import com.raquo.airstream.platform.JsArray

class ObserverList[Obs <: AnyRef](
  private val observers: JsArray[Obs]
) extends AnyVal {

  @inline def length: Int = observers.length

  @inline def apply(index: Int): Obs = observers(index)

  @inline def push(observer: Obs): Unit = observers.push(observer)

  /** @return whether observer was removed (`false` if it wasn't in the list) */
  def removeObserverNow(observer: Obs): Boolean = {
    val index = indexOfRef(observer)
    val shouldRemove = index != -1
    if (shouldRemove) {
      if (index == observers.length - 1) {
        val _ = observers.pop()
      } else {
        observers.splice(index, deleteCount = 1)
      }
    }
    shouldRemove
  }

  /** A subscription owns an instance, not an equality class. Scala.js array lookup already uses this identity. */
  private def indexOfRef(observer: Obs): Int = {
    var index = 0
    while (index < observers.length) {
      if (observers(index) eq observer) {
        return index
      }
      index += 1
    }
    -1
  }

  /** @param fn Must not throw */
  def foreach(fn: Obs => Unit): Unit = {
    var index = 0
    while (index < observers.length) {
      val observer = observers(index)
      index += 1 // Do this before invoking `fn` for a more graceful failure in case `fn` throws
      fn(observer)
    }
  }

}
