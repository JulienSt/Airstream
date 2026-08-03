package com.raquo.airstream

/** The Scala.js side of the platform layer.
  *
  * On this platform the growable array Airstream's core is built on is a JavaScript array, exactly as it always was. The
  * alias costs nothing at runtime: no wrapper is allocated, no method is forwarded, and the emitted code is identical to
  * what naming `com.raquo.ew.JsArray` directly produced. That is the point — introducing a platform layer must not make
  * the platform that already worked any slower.
  */
package object platform {

    type JsArray[A] = com.raquo.ew.JsArray[A]

    val JsArray: com.raquo.ew.JsArray.type = com.raquo.ew.JsArray
}
