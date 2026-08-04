package com.raquo.airstream.core

/** EventStream instance members for platforms without the JavaScript module-loader API. */
trait EventStreamInstancePlatformOps[+A] { this: EventStream[A] => }
