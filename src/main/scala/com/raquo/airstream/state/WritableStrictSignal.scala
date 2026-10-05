package com.raquo.airstream.state

import com.raquo.airstream.core.WritableSignal

import scala.util.Try

/** This trait needs to exist to make this override technically possible.
  * See the comment on [[StrictSignal.tryNow]].
  */
trait WritableStrictSignal[A]
extends StrictSignal[A]
with WritableSignal[A] {

  /** A read may evaluate and cache a lazy value, so it is a runtime operation and enters the runtime like a write. */
  override def tryNow(): Try[A] = runtime.run(super.tryNow())
}
