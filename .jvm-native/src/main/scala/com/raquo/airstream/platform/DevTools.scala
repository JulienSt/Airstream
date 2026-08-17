package com.raquo.airstream.platform

/** Where Airstream's diagnostics go on a platform with no browser console.
  *
  * Standard error by default, which is the conventional answer for a command-line program. It is also the wrong answer for
  * a terminal UI: such a program is usually holding the alternate screen, and a line written to stderr lands in the middle
  * of the picture and corrupts it — with no way for the application to redraw over something it never knew was written.
  *
  * Hence the replaceable destination. A terminal application sets its own on startup, typically one that collects the
  * messages and shows them after the screen is released, and gets diagnostics without a corrupted display.
  */
object DevTools {

  private val standardErrorSink: (DiagnosticLevel, String, Any) => Unit =
    (level, prefix, value) => System.err.println(s"[$level] $prefix $value")

  private var sink: (DiagnosticLevel, String, Any) => Unit = standardErrorSink

  /** Send `value` to the current destination, labelled with `prefix`. */
  def log(prefix: String, value: Any): Unit = sink(DiagnosticLevel.Log, prefix, value)

  /** Report something the developer should look at but that did not break anything. */
  def warn(message: String): Unit = sink(DiagnosticLevel.Warn, message, ())

  /** Report something that did break. */
  def error(message: String): Unit = sink(DiagnosticLevel.Error, message, ())

  /** Report a failure that happened while formatting another error, without bypassing the installed destination. */
  def consoleErrorCallbackFailed(err: Throwable): Unit =
    sink(DiagnosticLevel.Error, "Error in AirstreamError.consoleErrorCallback:", err)

  /** Send diagnostics somewhere other than the default from now on. */
  def setSink(destination: (DiagnosticLevel, String, Any) => Unit): Unit = sink = destination

  /** Send diagnostics back to the platform's default destination. */
  def resetSink(): Unit = sink = standardErrorSink

  /** Halt in an attached debugger, if there is one.
    *
    * There is no equivalent of a JavaScript debugger statement outside a JavaScript runtime, so this does nothing. It
    * exists so the debug operators compile and behave sanely here rather than being unavailable: a `debugBreak` in code
    * that also runs natively should be a no-op there, not a compile error.
    */
  def breakpoint(): Unit = ()
}
