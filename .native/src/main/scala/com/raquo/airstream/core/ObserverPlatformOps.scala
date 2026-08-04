package com.raquo.airstream.core

/** The members [[Observer]] offers only on Scala Native, of which there are none.
  *
  * Empty on purpose. The shared trait mixes this in once and each platform decides what it contributes; omitting the file
  * would break the shared declaration rather than say that this platform contributes nothing.
  *
  * What Scala.js adds here is a view of the observer as a JavaScript function. Outside a JavaScript runtime there is
  * nothing that would consume one, so its absence costs this platform nothing.
  */
trait ObserverPlatformOps[-A] { self: Observer[A] => }
