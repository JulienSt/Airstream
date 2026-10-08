package com.raquo.airstream.platform

import com.raquo.airstream.core.Runtime

import scala.scalajs.js

/** The browser developer tools operations Airstream has always used.
  *
  * The console is the default destination, exactly as before the platform split. The destination is now a setting of a
  * [[Runtime]] (`setSink`), so a page that runs several runtimes can route each one's diagnostics separately; the
  * debugger statement stays a direct call because there is nothing to route.
  */
object DevTools {

  /** Send `value` to the current destination, labelled with `prefix`. */
  def log(prefix: String, value: Any): Unit = Runtime.current.diagnosticSink.log(prefix, value)

  /** Report something the developer should look at but that did not break anything. */
  def warn(message: String): Unit = Runtime.current.diagnosticSink.warn(message, ())

  /** Report something that did break. */
  def error(message: String): Unit = Runtime.current.diagnosticSink.error(message, ())

  /** Report a failure that happened while formatting another error, without bypassing the installed destination. */
  def consoleErrorCallbackFailed(err: Throwable): Unit =
    Runtime.current.diagnosticSink.error("Error in AirstreamError.consoleErrorCallback:", err)

  /** Send the default runtime's diagnostics somewhere other than the console from now on. */
  def setSink(destination: (DiagnosticLevel, String, Any) => Unit): Unit = {
    Runtime.default.diagnostics = destination
  }

  /** Send the default runtime's diagnostics back to the console. */
  def resetSink(): Unit = {
    Runtime.default.resetDiagnostics()
  }

  @inline def breakpoint(): Unit =
    js.special.debugger()
}
