package com.raquo.airstream.core

import scala.scalajs.js

/** The conversions [[Sink]] offers only on Scala.js.
  *
  * `js.Function1` has no counterpart on any other platform, so the conversion from one cannot live in shared code. Putting
  * it in a trait the companion extends keeps it in the companion's implicit scope, which is where implicit resolution
  * looks — an implicit parked in some other object would compile and never be found.
  *
  * The Native counterpart of this file is empty on purpose. That is the whole mechanism: the shared companion says
  * `extends SinkPlatformOps` once, and each platform decides what that means.
  */
trait SinkPlatformOps {

  /** Treat a JavaScript callback as somewhere events can be sent. */
  implicit def jsCallbackToSink[A](callback: js.Function1[A, Unit]): Sink[A] = new Sink[A] {
    override def toObserver: Observer[A] = Observer(callback)
  }
}
