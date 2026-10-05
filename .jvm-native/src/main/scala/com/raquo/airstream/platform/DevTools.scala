package com.raquo.airstream.platform

import com.raquo.airstream.core.Runtime

/** Where Airstream's diagnostics go on a platform with no browser console.
  *
  * Standard error by default, which is the conventional answer for a command-line program. It is also the wrong answer for
  * a terminal UI: such a program is usually holding the alternate screen, and a line written to stderr lands in the middle
  * of the picture and corrupts it — with no way for the application to redraw over something it never knew was written.
  *
  * Hence the replaceable destination. A terminal application sets its own on startup, typically one that collects the
  * messages and shows them after the screen is released, and gets diagnostics without a corrupted display.
  *
  * The destination is a setting of a [[Runtime]]: `setSink` configures the default runtime, a runtime created with
  * `Runtime.confined()` starts with the destination in force at that moment, and a diagnostic goes to the sink of the
  * runtime whose turn reports it.
  */
object DevTools {

  /** Send `value` to the current destination, labelled with `prefix`. */
  def log(prefix: String, value: Any): Unit = Runtime.current.diagnostics(DiagnosticLevel.Log, prefix, value)

  /** Report something the developer should look at but that did not break anything. */
  def warn(message: String): Unit = Runtime.current.diagnostics(DiagnosticLevel.Warn, message, ())

  /** Report something that did break. */
  def error(message: String): Unit = Runtime.current.diagnostics(DiagnosticLevel.Error, message, ())

  /** Report a failure that happened while formatting another error, without bypassing the installed destination. */
  def consoleErrorCallbackFailed(err: Throwable): Unit =
    Runtime.current.diagnostics(DiagnosticLevel.Error, "Error in AirstreamError.consoleErrorCallback:", err)

  /** Send the default runtime's diagnostics somewhere other than the default from now on. */
  def setSink(destination: (DiagnosticLevel, String, Any) => Unit): Unit = {
    Runtime.default.diagnostics = destination
  }

  /** Send the default runtime's diagnostics back to the platform's default destination. */
  def resetSink(): Unit = {
    Runtime.default.diagnostics = DiagnosticSinks.standardError
  }

  /** Halt in an attached debugger, if there is one.
    *
    * There is no equivalent of a JavaScript debugger statement outside a JavaScript runtime, so this does nothing. It
    * exists so the debug operators compile and behave sanely here rather than being unavailable: a `debugBreak` in code
    * that also runs natively should be a no-op there, not a compile error.
    */
  def breakpoint(): Unit = ()
}
