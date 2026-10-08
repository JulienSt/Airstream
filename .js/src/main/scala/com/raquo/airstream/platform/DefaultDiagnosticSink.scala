package com.raquo.airstream.platform

import org.scalajs.dom

import scala.scalajs.js

/** Direct browser output does not need to construct level values or select a function-dispatch branch. */
private[airstream] object DefaultDiagnosticSink extends DiagnosticSink {

  override def log(prefix: String, value: Any): Unit =
    if (value != ()) dom.console.log(prefix, value.asInstanceOf[js.Any]) else dom.console.log(prefix)

  override def warn(prefix: String, value: Any): Unit =
    if (value != ()) dom.console.warn(prefix, value.asInstanceOf[js.Any]) else dom.console.warn(prefix)

  override def error(prefix: String, value: Any): Unit =
    if (value != ()) dom.console.error(prefix, value.asInstanceOf[js.Any]) else dom.console.error(prefix)

  override def function: (DiagnosticLevel, String, Any) => Unit = DiagnosticSinks.console
}
