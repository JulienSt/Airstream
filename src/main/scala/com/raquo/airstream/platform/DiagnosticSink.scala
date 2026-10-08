package com.raquo.airstream.platform

/** Internal dispatch keeps ordinary platform output independent of the optional function adapter and its level values.
  * Destinations are immutable; a new runtime snapshots the current destination by reference.
  */
abstract private[airstream] class DiagnosticSink {

  def log(prefix: String, value: Any): Unit

  def warn(prefix: String, value: Any): Unit

  def error(prefix: String, value: Any): Unit

  /** Preserve the configured function's identity for the existing runtime diagnostic accessor. */
  def function: (DiagnosticLevel, String, Any) => Unit
}

/** Only installing a custom function makes level-tagged dispatch reachable. */
final private[airstream] class FunctionDiagnosticSink(
  override val function: (DiagnosticLevel, String, Any) => Unit
) extends DiagnosticSink {

  override def log(prefix: String, value: Any): Unit = function(DiagnosticLevel.Log, prefix, value)

  override def warn(prefix: String, value: Any): Unit = function(DiagnosticLevel.Warn, prefix, value)

  override def error(prefix: String, value: Any): Unit = function(DiagnosticLevel.Error, prefix, value)
}
