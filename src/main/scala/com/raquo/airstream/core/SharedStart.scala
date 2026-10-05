package com.raquo.airstream.core

import com.raquo.airstream.platform.JsArray

/** The shared-start batching state of one runtime: whether a `shared` block is executing, the callbacks it has collected
  * and the transactions created while it ran. It used to be `Transaction.onStart`, a process-wide singleton, so a thread
  * starting its own observable while another thread was inside a shared start had its start event and its root
  * transaction filed under that other thread's block. Owned by a runtime, a block only ever collects
  * the work of the thread holding that runtime's permit.
  *
  * The batching rules are unchanged; see `Transaction.onStart` for the user-facing description.
  */
private[airstream] final class SharedStart(runtime: Runtime) {

  private var _isInSharedStart: Boolean = false

  private val pendingCallbacks: JsArray[Transaction => Unit] = JsArray()

  private val postStartTransactions: JsArray[Transaction] = JsArray()

  /** Whether we're executing synchronously inside a shared-start block.
    * Read by custom sources to decide how to schedule events that are fired onStart.
    */
  def isInSharedStart: Boolean = _isInSharedStart

  /** Delay `transaction` until the current shared-start block resolves. Only called while a block is executing. */
  def enqueuePostStart(transaction: Transaction): Unit = {
    postStartTransactions.push(transaction)
  }

  /** Run `code` as the (outermost) shared-start block unless one is already running or `when` is false. */
  def shared[A](code: => A, when: Boolean = true): A = {
    if (_isInSharedStart || !when) {
      // - We are already executing inside the `code` argument passed
      //   to another onStart.shared block, so adding another try-catch
      //   block is not necessary: that other block will take care of it.
      // - Or, the caller explicitly doesn't want a shared block now (!when)
      code
    } else {
      _isInSharedStart = true
      val result =
        try {
          code
        } finally {
          _isInSharedStart = false
          resolve()
        }
      result
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
        new Transaction(runtime, { _ =>
          while (postStartTransactions.length > 0) {
            runtime.transactions.add(postStartTransactions.shift())
          }
        })
      }
    } else {
      new Transaction(runtime, { trx =>
        // #TODO[Integrity] What if calling callback(trx) calls onStart.add?
        //  Is it ok to put it into the same list, or should it go into a new list,
        //  to be executed in a separate transaction?
        while (pendingCallbacks.length > 0) {
          val callback = pendingCallbacks.shift()
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
        while (postStartTransactions.length > 0) {
          val _trx = postStartTransactions.shift()
          runtime.transactions.add(_trx)
        }
      })
    }
  }
}
