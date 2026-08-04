package com.raquo.airstream.platform

object PlatformCallbacks {

  @inline def fromFunction[A](callback: A => Unit): JsCallback[A] = callback

  @inline def call[A](callback: JsCallback[A], value: A): Unit = callback(value)
}
