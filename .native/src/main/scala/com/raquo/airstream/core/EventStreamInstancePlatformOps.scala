package com.raquo.airstream.core

/** EventStream instance members supplied by Native. There is no module-loader API on this platform. */
trait EventStreamInstancePlatformOps[+A] { this: EventStream[A] => }
