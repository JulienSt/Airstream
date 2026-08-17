package com.raquo.airstream.core

/** Signal instance members for platforms without the JavaScript module-loader API. */
trait SignalInstancePlatformOps[+A] { this: Signal[A] => }
