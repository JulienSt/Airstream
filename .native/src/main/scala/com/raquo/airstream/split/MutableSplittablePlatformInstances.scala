package com.raquo.airstream.split

/** The [[MutableSplittable]] instances that only exist on Scala Native, of which there are none.
  *
  * Empty on purpose. Scala.js contributes one for a raw `js.Array`; that is a JavaScript type and has no counterpart here.
  * The growable array and the mutable buffer both have their instances in shared code, so nothing a Native program splits
  * in place is missing.
  */
trait MutableSplittablePlatformInstances
