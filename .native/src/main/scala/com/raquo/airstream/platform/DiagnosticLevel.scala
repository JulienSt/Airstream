package com.raquo.airstream.platform

/** How serious a diagnostic is. Carried to the sink so a consumer can treat an unhandled error differently from a debug
  * print — a terminal application may want to collect the first and drop the second.
  */
sealed trait DiagnosticLevel

object DiagnosticLevel {
  case object Log extends DiagnosticLevel
  case object Warn extends DiagnosticLevel
  case object Error extends DiagnosticLevel
}
