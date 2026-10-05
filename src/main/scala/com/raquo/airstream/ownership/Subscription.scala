package com.raquo.airstream.ownership

import com.raquo.airstream.core.Named

// @TODO[API] Change cleanup from () => Unit to : => Unit? Would it be possible to override such a field?
/** Represents a leaky resource that needs to be cleaned up.
  *
  * Subscription is linked for its life to a given owner.
  * It can be killed by that owner, or externally using .kill().
  *
  * See also: Ownership documentation, as well as [[Owner]] scaladoc
  *
  * @param cleanup Note: Must not throw!
  */
class Subscription(
  private[ownership] val owner: Owner,
  private var cleanup: () => Unit
) extends Named {

  /** Make sure we only kill any given Subscription once. Just a sanity check against bad user logic,
    * e.g. calling .kill() manually when `owner` has already killed this subscription.
    */
  final private var _isKilled = false

  owner.runtime.run(owner.own(this))

  /** True once this subscription was killed by its owner or externally; a read of its state, so it enters the runtime. */
  def isKilled: Boolean = owner.runtime.run(_isKilled)

  def kill(): Unit = owner.runtime.run {
    safeCleanup()
    owner.onKilledExternally(this)
  }

  // @TODO[API] This method exists only due to permissions. Is there another way?
  @inline private[ownership] def onKilledByOwner(): Unit = {
    safeCleanup()
  }

  private def safeCleanup(): Unit = {
    if (!_isKilled) {
      // Set _isKilled first before running user code (`cleanup()`) to avoid
      // a potential infinite loop if user code ends up calling .kill() on
      // this same subscription.
      _isKilled = true
      val cleanupNow = cleanup
      // A retained dead subscription should not retain the resources captured by cleanup.
      cleanup = Subscription.emptyCleanup
      cleanupNow()
    } else {
      throw new Exception("Can not kill Subscription: it was already killed.")
    }
  }
}

object Subscription {

  private val emptyCleanup: () => Unit = () => ()
}
