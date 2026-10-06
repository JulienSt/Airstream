package com.raquo.airstream.platform

/** Whether a second thread can exist in this program. On the JVM it always can. */
private[airstream] object Multithreading {

  /** Always true on the JVM; inline so the shared code that branches on it reads as on Scala Native. */
  inline def enabled: Boolean = true
}
