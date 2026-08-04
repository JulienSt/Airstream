package com.raquo.airstream.core

/** The conversions [[Sink]] offers only on Scala Native, of which there are none.
  *
  * Deliberately empty rather than absent. The shared companion says `extends SinkPlatformOps` once and each platform fills
  * that in; leaving the file out would break the shared code instead of expressing that this platform adds nothing.
  *
  * What the Scala.js side adds here is a conversion from `js.Function1`, which has no counterpart outside a JavaScript
  * runtime. A plain Scala function already reaches [[Sink]] through `Observer`, so nothing is missing on this platform —
  * only the JavaScript-shaped spelling of it.
  */
trait SinkPlatformOps
