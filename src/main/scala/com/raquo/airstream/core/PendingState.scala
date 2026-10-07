package com.raquo.airstream.core

/** Membership cached on built-in concrete nodes. Kept out of the public SyncObservable and CombineObservable traits so
  * precompiled custom implementations do not acquire a new abstract accessor they cannot implement.
  */
private[airstream] trait PendingState {

  final private[core] var isPending: Boolean = false
}
