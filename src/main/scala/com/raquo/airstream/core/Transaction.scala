package com.raquo.airstream.core

import com.raquo.airstream.combine.MergeStream
import com.raquo.airstream.core.AirstreamError.TransactionDepthExceeded
import com.raquo.airstream.custom.CustomSource
import com.raquo.airstream.platform.{undefined, UndefOr}
import com.raquo.airstream.util.JsPriorityQueue

/** Transaction is a moment in time during which Airstream guarantees no FRP glitches.
  *
  * Some observables need to emit their events in new transactions. Roughly speaking:
  *  - All async observables (e.g. delay(100))
  *  - All observables that can create loops in the observable graph (e.g. flatMapSwitch)
  *  - All observables that get their events from outside of Airstream (e.g. custom sources)
  *
  * An observable can only emit once in a given transaction.
  *  - See [[MergeStream]] or [[CustomSource]] for examples of handling that.
  *
  * See the docs for more details.
  *
  * A transaction belongs to the runtime it was created in: the public constructor takes the runtime the current thread
  * is inside (the default runtime when it is inside none), and Airstream's own observables pass theirs. Creating a
  * transaction enters that runtime, so a root `Transaction(...)` is a complete turn: it rejects a foreign thread before
  * touching any state, runs the transaction and its descendants, and releases the runtime on return.
  *
  * @param code Note: Must not throw!
  */
class Transaction private[airstream] (
  private[airstream] val runtime: Runtime,
  private[core] var code: Transaction => Any
) {

  /** Create a transaction in the runtime the current thread is inside, or in the default runtime. */
  def this(code: Transaction => Any) = this(Runtime.current, code)

  /** Priority queue of pending observables: sorted by their topoRank.
    *
    * Corollary: An Observable that is dequeue-d from here does not synchronously depend on any other pending observables
    */
  private var maybePendingObservables: UndefOr[JsPriorityQueue[SyncObservable[?]]] = undefined

  /**
    * Note: The transaction may be _actually scheduled_ one layer deeper
    * than this `depth` due to the `postStartTransactions` mechanism,
    * but this is good enough for `maxDepth` purposes.
    */
  private val depth: Int = runtime.run(runtime.transactions.peekStack().fold(1)(_.depth + 1))

  runtime.run {
    runtime.traceTransactionCreated()
    if (depth <= runtime.maxDepth || runtime.maxDepth == -1) {
      if (runtime.sharedStart.isInSharedStart) {
        // This delays scheduling transactions until the end of
        // the shared start transaction
        runtime.sharedStart.enqueuePostStart(this)
      } else {
        runtime.transactions.add(this)
      }
    } else {
      // Short circuit to break out of infinite loops without pinning CPU or crashing the whole app.
      // See e.g. https://github.com/raquo/Laminar/issues/116
      // This transaction will not be executed. Instead, it is reported in unhandled errors.
      // Other transactions will continue executing, if you have any that don't exceed the depth.
      // We don't throw an exception here because I'm afraid this could break things too violently,
      // and it's not like there is a reasonable way to locally handle such a condition anyway.
      runtime.sendUnhandledError(TransactionDepthExceeded(this, runtime.maxDepth))
    }
  }

  @inline private[core] def resolvePendingObservables(): Unit = {
    maybePendingObservables.foreach { pendingObservables =>
      while (pendingObservables.nonEmpty) {
        // Fire the first pending observable and remove it from the list
        pendingObservables.dequeue().syncFire(this)
      }
    }
  }

  private[airstream] def containsPendingObservable(observable: SyncObservable[?]): Boolean = {
    maybePendingObservables.map(_.contains(observable)).getOrElse(false)
  }

  private[airstream] def enqueuePendingObservable(observable: SyncObservable[?]): Unit = {
    val queue = maybePendingObservables.getOrElse {
      val newQueue = new JsPriorityQueue[SyncObservable[?]](Protected.topoRank)
      maybePendingObservables = newQueue
      newQueue
    }
    queue.enqueue(observable)
  }

}

object Transaction {

  /** Create new transaction.
    *
    * Typically used in internal observable code.
    *
    * Warning: It is rare that end users need to manually create transactions.
    * Example of legitimate use case: [[https://github.com/raquo/Airstream/#var-transaction-delay Var transaction delay]]
    */
  def apply(code: Transaction => Unit): Unit = new Transaction(code)

  /** Create a new transaction in `runtime`: the form Airstream's observables use, so a transaction is filed with the
    * runtime of the observable that emits in it rather than with whatever the current thread happens to be inside.
    */
  private[airstream] def apply(runtime: Runtime)(code: Transaction => Unit): Unit = new Transaction(runtime, code)

  /** How many _nested_ transactions you can have.
    *
    * Airstream will refuse to schedule any transaction that exceeds this limit.
    * This will prevent the content of the transaction from running, so this
    * could leave an event not fully propagated. This is, of course, an
    * undesirable situation, so you should never hit this limit.
    *
    * It is very hard to deliberately write valid code that would hit this
    * limit. If you are hitting this limit, most likely:
    *  - There is an (unterminated) infinite loop in your observable graph,
    *    e.g. two Var-s updating each other recursively, or
    *  - You are doing something that looks a lot like that for the first
    *    `maxDepth` transactions.
    *
    * You probably need to adjust your code instead of raising this limit,
    * but if you have a valid need for raising it, please let me know.
    *
    * You can set this to -1 to disable the check completely, but then
    * if you do actually have an infinite loop, your code will pin the
    * CPU and your single threaded Scala.js app will freeze – not
    * something you want, especially on your users' devices!
    *
    * This is the default runtime's limit. A runtime created with `Runtime.confined()` takes the value in force at its
    * creation and keeps its own copy (`runtime.maxDepth`).
    */
  def maxDepth: Int = Runtime.default.maxDepth

  def maxDepth_=(value: Int): Unit = {
    Runtime.default.maxDepth = value
  }

  /** This object holds a queue of callbacks that should be executed
    * when all observables finish starting. This lets `signal.changes`
    * streams emit the updated signal's value when restarting, in such
    * a way that the value propagates to all new observers instead of
    * just the first new observer that triggered restart.
    *
    * For that to happen, you need to wrap the code that's adding several
    * observers into `onStart.shared`. We do this in a couple places in
    * Airstream, and in a couple places in Laminar, and this seems to cover
    * most reasonable use cases. Users might need to wrap some of their code
    * into `onStart.shared` manually if they manage subscriptions manually.
    *
    * The state behind these methods belongs to a runtime (see [[Runtime]]); this object addresses the runtime the
    * current thread is inside, which is the default runtime for code that never creates another.
    *
    * See https://github.com/raquo/Airstream/#restarting-streams-that-depend-on-signals--signalchanges-
    */
  object onStart {

    /** Whether we're executing synchronously inside a shared-start block.
      * Read by custom sources to decide how to schedule events that are fired onStart.
      */
    def isInSharedStart: Boolean = Runtime.current.sharedStart.isInSharedStart

    /* Put the code that (potentially) adds more than one observer inside.
     * If that code causes `signal.updates` to restart (and emit the signal's
     * updated value), this event will be delayed until the rest of your code
     * in `shared` has finished executing. You can nest `shared` calls if
     * needed, and Airstream will wait for the outermost `shared` block to
     * finish running before executing all pendingCallbacks. This logic fires
     * `signal.updates` re-sync events, and single start-emissions of custom
     * sources (e.g. `fromValue`) - see CustomStreamSource.
     *
     * To be more specific, once the outermost `shared` block finishes executing,
     * a new transaction will be created, and inside of it, all pending callbacks
     * will be executed. Aside from having the benefit of executing after all
     * the desired observers have been added, this also has the benefit of sending
     * out all of those events in the same transaction. This is done to eliminate
     * glitches (Airstream can avoid FRP glitches only inside a single transaction),
     * however this can introduce other glitch-like differences in behaviour
     * compared to the normal flow of events, e.g. observables that could otherwise
     * never possibly emit an event in the same transaction, might do so when
     * they're both triggered by this mechanism at the same time. However, in
     * practice this should hopefully be almost unnoticeable, as the conditions
     * required to trigger this mechanism are rather specific, and the expected
     * type of glitches are less likely to be disruptive than the usual ones.
     *
     * If you rely on standard Laminar features for automatic management of
     * subscriptions, you shouldn't ever need to call this manually.
     *
     * See https://github.com/raquo/Airstream/#restarting-streams-that-depend-on-signals--signalchanges-
     */
    def shared[A](code: => A, when: Boolean = true): A = {
      val runtime = Runtime.current
      runtime.run(runtime.sharedStart.shared(code, when))
    }

    /** Add a callback to execute once the new shared transaction gets executed.
      *
      * This must only be called while an onStart.shared block is executing.
      *
      * In Airstream, it's not possible to start an observable without being
      * inside an onStart.shared block (see e.g. [[WritableObservable.addObserver]]
      * and [[WritableObservable.addInternalObserver]]), so this is safe to call
      * from inside an [[Observable.onStart]].
      *
      * @param callback - Must not throw!
      */
    def add(callback: Transaction => Unit): Unit = Runtime.current.sharedStart.add(callback)
  }

  private[core] def isClearState: Boolean = Runtime.current.transactions.isClearState

  private[airstream] def currentTransaction(): UndefOr[Transaction] = Runtime.current.transactions.peekStack()
}
