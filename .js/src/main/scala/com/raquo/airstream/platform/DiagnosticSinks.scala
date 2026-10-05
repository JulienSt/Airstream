package com.raquo.airstream.platform

import org.scalajs.dom

import scala.scalajs.js

/** The destination diagnostics take in a browser until a runtime is given another one: the developer console, the
  * same calls Airstream has always made.
  */
object DiagnosticSinks {

  /** The destination a runtime starts with on this platform. */
  def default: (DiagnosticLevel, String, Any) => Unit = console

  /** Log, warn or error on the console; a `()` value is left out so a bare message prints as before. */
  val console: (DiagnosticLevel, String, Any) => Unit = (level, prefix, value) => {
    val hasValue = value != ()
    level match {
      case DiagnosticLevel.Log =>
        if (hasValue) dom.console.log(prefix, value.asInstanceOf[js.Any]) else dom.console.log(prefix)
      case DiagnosticLevel.Warn =>
        if (hasValue) dom.console.warn(prefix, value.asInstanceOf[js.Any]) else dom.console.warn(prefix)
      case DiagnosticLevel.Error =>
        if (hasValue) dom.console.error(prefix, value.asInstanceOf[js.Any]) else dom.console.error(prefix)
    }
  }
}
