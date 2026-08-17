package com.raquo.airstream.dynamicImport

import annotation.nowarn
import com.raquo.airstream.core.EventStream
import com.raquo.airstream.timing.JsPromiseStream

import scala.scalajs.js

trait DynamicImportStreamOps[+A] { this: EventStream[A] =>

  /** Create an ECMAScript 6 Dynamic import boundary for progressive module loading.
    *
    * Any code required for `resource` (and not required elsewhere) will be loaded
    * only if and when it's actually executed. This means that there will be an
    * asynchronous delay while the code is being downloaded by the browser.
    *
    * This uses Scala.js feature [[js.dynamicImport]] under the hood.
    */
  @nowarn("msg=New anonymous class definition will be duplicated at each inline site") // `resource` Function1 anon class – NBD
  inline def dynamicImport[R](inline resource: A => R): EventStream[R] = {
    flatMapSwitch { v =>
      new JsPromiseStream(js.dynamicImport(resource), emitOnce = false) // #Note: `resource`, not `resource(v)`!
        .map(_(v)) // execute the `resource` function once it's loaded
    }
  }

}
