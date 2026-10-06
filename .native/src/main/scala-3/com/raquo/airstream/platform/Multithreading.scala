package com.raquo.airstream.platform

import scala.scalanative.meta.LinktimeInfo

/** Whether a second thread can exist in this program: a Scala Native program decides it when it is linked
  * (`nativeConfig.withMultithreading`).
  */
private[airstream] object Multithreading {

  /** The link-time constant itself, inlined into the condition of the caller's `if`, so the linker resolves that branch and drops
    * the side a program linked the other way never runs.
    */
  inline def enabled: Boolean = LinktimeInfo.isMultithreadingEnabled
}
