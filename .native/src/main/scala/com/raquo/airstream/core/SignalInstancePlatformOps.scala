package com.raquo.airstream.core

/** Signal instance members supplied by Native. There is no module-loader API on this platform. */
trait SignalInstancePlatformOps[+A] { this: Signal[A] => }
