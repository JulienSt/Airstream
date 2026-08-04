package com.raquo.airstream.platform

import scala.scalajs.js

import org.scalajs.dom

/** Where Airstream's diagnostics go on Scala.js.
  *
  * The browser console by default, which is where a web developer already looks. The destination stays replaceable anyway,
  * because a consumer embedding Airstream in something other than a page may want it elsewhere.
  */
object DevTools {

  private val consoleSink: (DiagnosticLevel, String, Any) => Unit = (level, prefix, value) =>
    level match {
      case DiagnosticLevel.Log   => dom.console.log(prefix, value.asInstanceOf[js.Any])
      case DiagnosticLevel.Warn  => dom.console.warn(prefix)
      case DiagnosticLevel.Error => dom.console.error(prefix)
    }

  private var sink: (DiagnosticLevel, String, Any) => Unit = consoleSink

  /** Send `value` to the current destination, labelled with `prefix`. */
  def log(prefix: String, value: Any): Unit = sink(DiagnosticLevel.Log, prefix, value)

  /** Report something the developer should look at but that did not break anything. */
  def warn(message: String): Unit = sink(DiagnosticLevel.Warn, message, ())

  /** Report something that did break. */
  def error(message: String): Unit = sink(DiagnosticLevel.Error, message, ())

  /** Send diagnostics somewhere other than the default from now on. */
  def setSink(destination: (DiagnosticLevel, String, Any) => Unit): Unit = sink = destination

  /** Send diagnostics back to the platform's default destination. */
  def resetSink(): Unit = sink = consoleSink

  /** Halt in an attached debugger, if there is one.
    *
    * Does nothing when nothing is attached, which is why a debug operator accidentally left in shipped code is an
    * annoyance rather than an outage.
    */
  def breakpoint(): Unit = js.special.debugger()
}
