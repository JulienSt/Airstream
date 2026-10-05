package com.raquo.airstream.platform

/** The destination diagnostics take on a platform with no browser console, until a runtime is given another one.
  *
  * Standard error is the conventional answer for a command-line program and the wrong one for a terminal UI holding
  * the alternate screen, which is why the destination is a per-runtime setting (`DevTools.setSink`) and this is only
  * its starting value.
  */
object DiagnosticSinks {

  /** The destination a runtime starts with on this platform. */
  def default: (DiagnosticLevel, String, Any) => Unit = standardError

  /** Print every diagnostic on standard error, labelled with its level. */
  val standardError: (DiagnosticLevel, String, Any) => Unit =
    (level, prefix, value) => System.err.println(s"[$level] $prefix $value")
}
