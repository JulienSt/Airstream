package com.raquo.airstream.core

import com.raquo.airstream.platform.{JsArray, JsMap}
import com.raquo.airstream.platform.{undefined, UndefOr}

import scala.annotation.tailrec

/** The transaction scheduling state of one runtime: the stack of transactions being executed and the children each of
  * them has queued. It used to live in `Transaction.pendingTransactions`, a process-wide singleton, which is how an
  * unrelated graph on another thread could be filed as a child of a transaction it never knew about.
  * Owned by a runtime, it is only ever touched by the thread holding that runtime's permit.
  *
  * The scheduling rules are unchanged: a root transaction runs at once, a transaction created while another is running
  * becomes its child and runs after it, children run depth-first in creation order.
  */
private[airstream] final class TransactionEngine(runtime: Runtime) {

  /** First transaction is the top of the stack, currently running.
    * That transaction's parent transaction is the second item, and so on.
    */
  private val stack: JsArray[Transaction] = JsArray()

  private val children: JsMap[Transaction, JsArray[Transaction]] = new JsMap()

  def add(newTransaction: Transaction): Unit = {
    // 1. Regarding calling `run`:
    //    If a transaction is currently running, the new transaction will be triggered
    //    from the .done() call after the current transaction finishes.
    //    Otherwise, if there are no pending transactions other than this new transaction,
    //    we need to run this transaction right now because no one will do it for us.
    // 2. Regarding the queue:
    //    If a transaction is currently running, add newTransaction to its children.
    //    They will run after the current transaction finishes.
    peekStack().fold {
      // #TODO[Performance] This pushToStack is taking up 15% of cpu time on a trivial eventbus --> Observer.empty benchmark.
      //  Should we try to optimize it? Since we run the trx immediately, perhaps we could simply set a flag instead of pushing it to the array?
      //  Consider this later when I have moer comprehensive benchmarks.
      pushToStack(newTransaction)
      run(newTransaction)
      done(newTransaction)
    } { currentTransaction =>
      enqueueChild(parent = currentTransaction, newChild = newTransaction)
    }
  }

  /** #Warning: you MUST call done(trx) after calling `run(trx)`! */
  @tailrec def done(transaction: Transaction): Unit = {
    if (!peekStack().contains(transaction)) {
      // @TODO[Integrity] Should we really throw here?
      throw new Exception("Transaction queue error: Completed transaction is not the first in stack. This is a bug in Airstream.")
    }

    putNextTransactionOnStack(doneTransaction = transaction)

    transaction.code = TransactionEngine.throwDeadTrxError // stop holding up `trx` contents in memory

    val maybeNextTransaction = peekStack()
    if (maybeNextTransaction == undefined) {
      if (children.size > 0) {
        var numChildren = 0
        children.forEach((transactions, _) => numChildren += transactions.length)
        throw new Exception(s"Transaction queue error: Stack cleared, but a total of ${numChildren} children for ${children.size} transactions remain. This is a bug in Airstream.")
      }
    } else {
      val nextTransaction = maybeNextTransaction.asInstanceOf[Transaction]
      run(nextTransaction)
      done(nextTransaction)
    }
  }

  /* If this transaction has children remaining, set first child to be run next.
   * Otherwise, remove transaction from the stack and do the same for next transaction on stack.
   */
  @tailrec private def putNextTransactionOnStack(doneTransaction: Transaction): Unit = {
    // We use depth-first because of https://github.com/raquo/Airstream/issues/39
    val maybeNextChildTrx = dequeueChild(parent = doneTransaction)
    if (maybeNextChildTrx == undefined) {
      // No children, this transaction is truly done now, remove it from the stack.
      popStack()
      // If any transactions left in the stack, recurse
      val maybeParentTransaction = peekStack()
      if (maybeParentTransaction != undefined) {
        val parentTransaction = maybeParentTransaction.asInstanceOf[Transaction]
        putNextTransactionOnStack(doneTransaction = parentTransaction)
      }
    } else {
      val nextChildTrx = maybeNextChildTrx.asInstanceOf[Transaction]
      // Found a child transaction, so put it on the stack, so that it wil run next.
      // Once that child is all done, it will be popped from the stack, and we will
      pushToStack(nextChildTrx)
    }
  }

  /** Returns the top of the stack, i.e. the currently executing transaction, if any. */
  def peekStack(): UndefOr[Transaction] = {
    // in Javascript, if array is empty, this does not fail, but instead returns `undefined`.
    stack(0)
  }

  def isClearState: Boolean = stack.length == 0 && children.size == 0

  private def maybeChildrenFor(transaction: Transaction): UndefOr[JsArray[Transaction]] = {
    children.get(transaction)
  }

  private def pushToStack(transaction: Transaction): Unit = {
    stack.unshift(transaction)
  }

  private def popStack(): UndefOr[Transaction] = {
    // JsArray.shift returns `undefined` if array is empty
    stack.shift()
  }

  private def enqueueChild(parent: Transaction, newChild: Transaction): Unit = {
    val maybeChildren = maybeChildrenFor(parent)
    val noChildrenFound = maybeChildren.isEmpty
    val newChildren = maybeChildren.getOrElse(JsArray())
    newChildren.push(newChild)
    if (noChildrenFound) {
      children.set(parent, newChildren)
    }
  }

  private def dequeueChild(parent: Transaction): UndefOr[Transaction] = {
    val maybeParentChildren = maybeChildrenFor(parent)
    maybeParentChildren.filter(_.length > 0).map { parentChildren =>
      val nextChild = parentChildren.shift()
      if (parentChildren.length == 0) {
        children.delete(parent)
      }
      nextChild
    }
  }

  /** #Warning: you MUST call `done(trx)` after calling `run(trx)`! */
  private def run(transaction: Transaction): Unit = {
    try {
      transaction.code(transaction)
      transaction.resolvePendingObservables()
    } catch {
      case err: Throwable =>
        // #TODO[Integrity] I'm not 100% sure that this is what we want to do here,
        //  but I think it's better than not handling the error at all.
        runtime.sendUnhandledError(err)
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
