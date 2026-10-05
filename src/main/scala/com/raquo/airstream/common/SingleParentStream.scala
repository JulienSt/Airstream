package com.raquo.airstream.common

import com.raquo.airstream.core.{InternalObserver, Observable, Protected, Runtime, WritableStream}

/** A simple stream that only has one parent. */
trait SingleParentStream[I, O] extends WritableStream[O] with InternalObserver[I] {

  protected val parent: Observable[I]

  /** A synchronous edge stays inside one runtime, so this stream lives where its parent lives. */
  override protected[airstream] val runtime: Runtime = Runtime.inheritedFrom(parent)

  override protected def onWillStart(): Unit = {
    // println(s"${this} > onWillStart")
    Protected.maybeWillStart(parent)
  }

  override protected def onStart(): Unit = {
    // println(s"${this} > onStart")
    parent.addInternalObserver(this, shouldCallMaybeWillStart = false)
    super.onStart()
  }

  override protected def onStop(): Unit = {
    parent.removeInternalObserver(observer = this)
    super.onStop()
  }
}
