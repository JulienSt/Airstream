package com.raquo.airstream.platform

/** The destination diagnostics take in a browser until a runtime is given another one: the developer console, the
  * same calls Airstream has always made.
  */
object DiagnosticSinks {

  /** The destination a runtime starts with on this platform. */
  def default: (DiagnosticLevel, String, Any) => Unit = console

  /** Log, warn or error on the console; a `()` value is left out so a bare message prints as before. */
  val console: (DiagnosticLevel, String, Any) => Unit = (level, prefix, value) => {
    level match {
      case DiagnosticLevel.Log =>
        DefaultDiagnosticSink.log(prefix, value)
      case DiagnosticLevel.Warn =>
        DefaultDiagnosticSink.warn(prefix, value)
      case DiagnosticLevel.Error =>
        DefaultDiagnosticSink.error(prefix, value)
    }
  }
}
