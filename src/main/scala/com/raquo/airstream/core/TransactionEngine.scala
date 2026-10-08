package com.raquo.airstream.core

import com.raquo.airstream.platform.{undefined, UndefOr}

/** Scheduling state owned by one runtime. Only its permit holder may enter or drain this engine.
  *
  * New transactions created during a turn are deferred children. At the end of that transaction, pushing its children
  * onto the pending stack in reverse creation order makes the oldest child next, ahead of every older sibling. Thus
  * depth-first order needs neither an ancestor stack nor a parent-to-children map. Buffers are reused between turns,
  * but clear every consumed reference; transaction objects themselves are never reused.
  */
final private[airstream] class TransactionEngine(runtime: Runtime) {

  private var current: UndefOr[Transaction] = undefined

  private val children = new TransactionStack

  private val pending = new TransactionStack

  private var pendingObservables: UndefOr[PendingObservables] = undefined

  def containsPendingObservable(transaction: Transaction, observable: SyncObservable[?]): Boolean = {
    requireCurrent(transaction)
    pendingObservables.isDefined && pendingObservables.get.contains(observable)
  }

  def enqueuePendingObservable(transaction: Transaction, observable: SyncObservable[?]): Unit = {
    requireCurrent(transaction)
    if (pendingObservables.isEmpty) pendingObservables = new PendingObservables
    pendingObservables.get.enqueue(observable)
  }

  def resolvePendingObservables(transaction: Transaction): Unit = {
    requireCurrent(transaction)
    if (pendingObservables.isDefined) {
      val queue = pendingObservables.get
      while (queue.nonEmpty) queue.dequeue().syncFire(transaction)
    }
  }

  private def requireCurrent(transaction: Transaction): Unit = {
    if (current.isEmpty || (current.get ne transaction)) {
      throw new IllegalStateException("Pending observables require the currently executing transaction")
    }
  }

  def add(newTransaction: Transaction): Unit = {
    if (current.isDefined) {
      children.push(newTransaction)
    } else {
      current = newTransaction
      try {
        drain()
      } finally {
        current = undefined
        children.clear()
        pending.clear()
      }
    }
  }

  private def drain(): Unit = {
    while (current.isDefined) {
      val transaction = current.get
      try {
        run(transaction)
      } finally {
        transaction.code = TransactionEngine.throwDeadTrxError
      }
      while (children.nonEmpty) {
        pending.push(children.pop())
      }
      if (pending.nonEmpty) {
        current = pending.pop()
      } else {
        current = undefined
      }
    }
  }

  /** The currently executing transaction, if any; a completed parent is not an executing transaction. */
  def peekStack(): UndefOr[Transaction] = current

  /** Depth is stored on each transaction when created, so pending siblings need no live ancestor objects. */
  def depthOfNewTransaction: Int = if (current.isEmpty) 1 else current.get.depthOfChild

  def isClearState: Boolean =
    current.isEmpty && !children.nonEmpty && !pending.nonEmpty &&
      (pendingObservables.isEmpty || !pendingObservables.get.nonEmpty)

  /** Errors are reported inside the same turn; queued descendants retain their depth-first position. */
  private def run(transaction: Transaction): Unit = {
    // Keep finalization outside the catch frame: on Native 0.5.12 a rethrow from that frame's catch can skip its finally.
    // An enclosing finally preserves cleanup even when the explicitly unsafe error callback rethrows.
    try {
      try {
        transaction.code(transaction)
        transaction.resolvePendingObservables()
      } catch {
        case err: Throwable =>
          // #TODO[Integrity] I'm not 100% sure that this is what we want to do here,
          //  but I think it's better than not handling the error at all.
          runtime.sendUnhandledError(err)
      }
    } finally {
      if (pendingObservables.isDefined) pendingObservables.get.clear()
    }
    // @TODO[API,Integrity]
    //  Errors in `code` are routed to unhandled errors, so `Transaction(code)` only throws
    //  when an unhandled-error callback itself throws (e.g. the unsafe rethrow callback).
    //  This is not very predictable, so we should fix it.
  }
}

private[airstream] object TransactionEngine {

  private val throwDeadTrxError: Transaction => Any = { trx =>
    throw new Exception(s"Attempted to run Transaction $trx after it was already executed.")
  }
}
