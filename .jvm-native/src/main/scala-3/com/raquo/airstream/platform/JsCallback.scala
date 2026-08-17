package com.raquo.airstream.platform

type JsCallback[-A] = A => Unit

object PlatformCallbacks {

    def fromFunction[A](callback: A => Unit): JsCallback[A] = callback

    def call[A](callback: JsCallback[A], value: A): Unit = callback(value)
}
