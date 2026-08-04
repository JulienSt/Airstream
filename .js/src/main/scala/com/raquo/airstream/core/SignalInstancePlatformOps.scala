package com.raquo.airstream.core

import com.raquo.airstream.dynamicImport.DynamicImportSignalOps

/** Signal instance members supplied by the JavaScript platform. */
trait SignalInstancePlatformOps[+A] extends DynamicImportSignalOps[A] { this: Signal[A] => }
