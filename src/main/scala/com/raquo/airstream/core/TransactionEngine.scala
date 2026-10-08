package com.raquo.airstream.core

import com.raquo.airstream.platform.{undefined, JsArray, UndefOr}

/** Scheduling and shared-start batching state owned by one runtime. Only its permit holder may enter or drain this engine.
  *
  * New transactions created during a turn are deferred children. At the end of that transaction, pushing its children
  * onto the pending stack in reverse creation order makes the oldest child next, ahead of every older sibling. Thus
  * depth-first order needs neither an ancestor stack nor a parent-to-children map. Buffers are reused between turns,
  * but clear every consumed reference; transaction objects themselves are never reused.
  */
final private[airstream] class TransactionEngine(runtime: Runtime) {

  // These private slots contain only constructed objects; null means absent. peekStack keeps the UndefOr boundary.
  private var current: Transaction = null

  private val children = new TransactionStack

  private val pending = new TransactionStack

  private var pendingObservables: PendingObservables = null

  def containsPendingObservable(transaction: Transaction, observable: SyncObservable[?]): Boolean = {
    requireCurrent(transaction)
    (pendingObservables ne null) && pendingObservables.contains(observable)
  }

  def enqueuePendingObservable(transaction: Transaction, observable: SyncObservable[?]): Unit = {
    requireCurrent(transaction)
    if (pendingObservables eq null) pendingObservables = new PendingObservables
    pendingObservables.enqueue(observable)
  }

  def resolvePendingObservables(transaction: Transaction): Unit = {
    requireCurrent(transaction)
    if (pendingObservables ne null) {
      val queue = pendingObservables
      while (queue.nonEmpty) queue.dequeue().syncFire(transaction)
    }
  }

  private def requireCurrent(transaction: Transaction): Unit = {
    if ((current eq null) || (current ne transaction)) {
      throw new IllegalStateException("Pending observables require the currently executing transaction")
    }
  }

  def add(newTransaction: Transaction): Unit = {
    if (current ne null) {
      children.push(newTransaction)
    } else {
      current = newTransaction
      try {
        drain()
      } finally {
        current = null
        children.clear()
        pending.clear()
      }
    }
  }

  private def drain(): Unit = {
    while (current ne null) {
      val transaction = current
      try {
        run(transaction)
      } finally {
        transaction.code = AirstreamError.throwDeadTransactionError
      }
      while (children.nonEmpty) {
        pending.push(children.pop())
      }
      if (pending.nonEmpty) {
        current = pending.pop()
      } else {
        current = null
      }
    }
  }

  /** The currently executing transaction, if any; a completed parent is not an executing transaction. */
  def peekStack(): UndefOr[Transaction] = if (current eq null) undefined else current

  /** Depth is stored on each transaction when created, so pending siblings need no live ancestor objects. */
  def depthOfNewTransaction: Int = if (current eq null) 1 else current.depthOfChild

  def isClearState: Boolean =
    (current eq null) && !children.nonEmpty && !pending.nonEmpty &&
      ((pendingObservables eq null) || !pendingObservables.nonEmpty)

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
      if (pendingObservables ne null) pendingObservables.clear()
    }
    // @TODO[API,Integrity]
    //  Errors in `code` are routed to unhandled errors, so `Transaction(code)` only throws
    //  when an unhandled-error callback itself throws (e.g. the unsafe rethrow callback).
    //  This is not very predictable, so we should fix it.
  }

  /** The shared-start batching state of one runtime: whether a `shared` block is executing, the callbacks it has collected
    * and the transactions created while it ran. It used to be `Transaction.onStart`, a process-wide singleton, so a thread
    * starting its own observable while another thread was inside a shared start had its start event and its root
    * transaction filed under that other thread's block. Owned by a runtime, a block only ever collects
    * the work of the thread holding that runtime's permit.
    *
    * The batching rules are unchanged; see `Transaction.onStart` for the user-facing description.
    */

  private var _isInSharedStart: Boolean = false

  private val pendingCallbacks: JsArray[Transaction => Unit] = JsArray()

  private var callbackIndex = 0

  private val postStartTransactions: JsArray[Transaction] = JsArray()

  private var postStartIndex = 0

  /** Whether we're executing synchronously inside a shared-start block.
    * Read by custom sources to decide how to schedule events that are fired onStart.
    */
  def isInSharedStart: Boolean = _isInSharedStart

  /** Original binary flag setter, called only after the compatibility facade enters this runtime. */
  private[core] def setSharedStartFlag(value: Boolean): Unit = {
    _isInSharedStart = value
  }

  /** Delay `transaction` until the current shared-start block resolves. Only called while a block is executing. */
  def enqueuePostStart(transaction: Transaction): Unit = {
    postStartTransactions.push(transaction)
  }

  /** Run `code` as the (outermost) shared-start block unless one is already running or `when` is false. */
  @inline def shared[A](code: => A, when: Boolean = true): A = {
    val resolveAfter = !_isInSharedStart && when
    if (resolveAfter) {
      _isInSharedStart = true
    }
    // One use of the by-name block lets the JS optimizer inline it without retaining a callable for two branches.
    try {
      code
    } finally {
      if (resolveAfter) {
        _isInSharedStart = false
        resolve()
      }
    }
  }

  /** Add a callback to execute once the new shared transaction gets executed.
    *
    * This must only be called while a shared-start block is executing.
    *
    * @param callback - Must not throw!
    */
  def add(callback: Transaction => Unit): Unit = {
    if (!_isInSharedStart) {
      throw new Exception("Transaction.onStart.add was called outside of a Transaction.onStart.shared block. This is likely a bug in Airstream.")
    }
    pendingCallbacks.push(callback)
  }

  private def resolve(): Unit = {
    if (pendingCallbacks.length == 0) {
      if (postStartTransactions.length > 0) {
        new Transaction(runtime,
          { _ =>
            drainPostStartTransactions()
          })
      }
    } else {
      new Transaction(runtime,
        { trx =>
          // #TODO[Integrity] What if calling callback(trx) calls onStart.add?
          //  Is it ok to put it into the same list, or should it go into a new list,
          //  to be executed in a separate transaction?
          while (pendingCallbacks.length > 0) {
            val callback = nextCallback()
            try {
              callback(trx)
            } catch {
              case err: Throwable =>
                // #TODO[Integrity] I'm not 100% sure that this is what we need to do here.
                runtime.sendUnhandledError(err)
            }
          }
          // Any transactions created during the shared start
          // that weren't converted to callbacks, will now be
          // scheduled, and will be executed after this shared
          // transaction finishes (they are marked as its
          // children), in the same order as they were created.
          drainPostStartTransactions()
        })
    }
  }

  /** Release a consumed reference before calling user code. Clearing at the last slot preserves the old empty-queue
    * observation inside a nested shared, and a throwing callback leaves the cursor at the next unconsumed entry.
    */
  private def nextCallback(): Transaction => Unit = {
    val callback = pendingCallbacks(callbackIndex)
    pendingCallbacks(callbackIndex) = null
    callbackIndex += 1
    if (callbackIndex == pendingCallbacks.length) {
      pendingCallbacks.length = 0
      callbackIndex = 0
    }
    callback
  }

  /** Scheduling only queues children of the running transaction. Reuse the array without shifting its remaining tail. */
  private def drainPostStartTransactions(): Unit = {
    while (postStartTransactions.length > 0) {
      val transaction = postStartTransactions(postStartIndex)
      postStartTransactions(postStartIndex) = null
      postStartIndex += 1
      if (postStartIndex == postStartTransactions.length) {
        postStartTransactions.length = 0
        postStartIndex = 0
      }
      runtime.transactions.add(transaction)
    }
  }
}
