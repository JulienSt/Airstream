package com.raquo.airstream.dynamicImport

import annotation.nowarn
import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.airstream.timing.JsPromiseStream

import scala.scalajs.js

trait DynamicImportStreamObjectOps { this: EventStream.type =>

  /** Create an ECMAScript 6 Dynamic import boundary for progressive module loading.
    *
    * Any code required for `resource` (and not required elsewhere) will be loaded
    * only if and when it's actually executed. This means that there will be an
    * asynchronous delay while the code is being downloaded by the browser.
    *
    * This uses Scala.js feature [[js.dynamicImport]] under the hood.
    */
  @nowarn("msg=New anonymous class definition will be duplicated at each inline site") // `promise :=>` Function0 anon class – NBD
  inline def dynamicImport[R](inline resource: R): EventStream[R] =
    new JsPromiseStream(js.dynamicImport(resource), emitOnce = false)

}
