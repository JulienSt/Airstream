package com.raquo.airstream.core

import scala.scalajs.js

/** The members [[Observer]] offers only on Scala.js.
  *
  * A JavaScript-callable view of an observer has no meaning outside a JavaScript runtime, so it cannot live in shared
  * code. Mixing it in through a trait keeps it a member of every Observer on this platform, which is what the existing
  * call sites expect — a helper object would compile and change every one of them.
  *
  * The Native counterpart is empty, which is the mechanism working as intended rather than something missing.
  */
trait ObserverPlatformOps[-A] { self: Observer[A] =>

  /** This observer as a plain JavaScript function, for handing to code that expects a callback. */
  lazy val toJsFn1: js.Function1[A, Unit] = onNext _
}
