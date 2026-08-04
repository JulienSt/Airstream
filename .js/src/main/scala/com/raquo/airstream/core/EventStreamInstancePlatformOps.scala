package com.raquo.airstream.core

import com.raquo.airstream.dynamicImport.DynamicImportStreamOps

/** EventStream instance members supplied by the JavaScript platform. */
trait EventStreamInstancePlatformOps[+A] extends DynamicImportStreamOps[A] { this: EventStream[A] => }
