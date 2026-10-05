package com.raquo.airstream.core

/** Scala.js has no thread to block, so a receipt is awaited through its futures alone. */
trait ReceiptPlatformOps { self: Receipt => }
