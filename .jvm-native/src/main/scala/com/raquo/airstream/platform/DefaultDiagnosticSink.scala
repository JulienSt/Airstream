package com.raquo.airstream.platform

/** Preserve the platform's labelled standard-error output without constructing diagnostic level values. */
private[airstream] trait DefaultDiagnosticSink extends DiagnosticSink {

  override def log(prefix: String, value: Any): Unit = System.err.println(s"[Log] $prefix $value")

  override def warn(prefix: String, value: Any): Unit = System.err.println(s"[Warn] $prefix $value")

  override def error(prefix: String, value: Any): Unit = System.err.println(s"[Error] $prefix $value")

  override def function: (DiagnosticLevel, String, Any) => Unit = DiagnosticSinks.standardError
}

/** The existing function facade can use the same stateless platform operations. */
private[airstream] object DefaultDiagnosticSink extends DefaultDiagnosticSink
