package com.raquo.airstream.split

/** The [[Splittable]] instances that only exist on Scala Native, of which there are none.
  *
  * Empty on purpose, like the other platform traits. Scala.js contributes instances for a raw `js.Array` and a
  * `JsVector`; both are JavaScript types, so there is nothing here for them to be about.
  *
  * Nothing is lost. Every collection a Native program actually splits — List, Vector, Seq, Buffer, and the platform's own
  * growable array — has its instance in shared code.
  */
trait SplittablePlatformInstances
