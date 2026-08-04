package com.raquo.airstream.platform

import org.scalajs.dom

import scala.scalajs.js

/** The browser developer tools operations Airstream has always used.
  *
  * These tiny methods are inlined deliberately: the Scala.js artifact keeps the same direct console calls and debugger
  * statement it had before the platform split. Native supplies a replaceable sink instead.
  */
object DevTools {

  @inline def log(prefix: String, value: Any): Unit =
    dom.console.log(prefix, value.asInstanceOf[js.Any])

  @inline def warn(message: String): Unit =
    dom.console.warn(message)

  @inline def error(message: String): Unit =
    dom.console.error(message)

  @inline def consoleErrorCallbackFailed(err: Throwable): Unit = {
    dom.console.error("Error in AirstreamError.consoleErrorCallback:")
    dom.console.error(err)
  }

  @inline def breakpoint(): Unit =
    js.special.debugger()
}
