package com.raquo.airstream.custom

import com.raquo.airstream.core.{EventStream, Transaction, WritableStream}
import com.raquo.airstream.custom.CustomSource._
import com.raquo.airstream.platform.JsArray

/** Use this to easily create a custom signal from an external source
  *
  * See docs on custom sources, and [[CustomSource.Config]]
  */
class CustomStreamSource[A](
  makeConfig: (FireValue[A], FireError, GetStartIndex, GetIsStarted) => CustomSource.Config,
) extends WritableStream[A] with CustomSource[A] {

  /** Events emitted synchronously while (re)starting are collected here (as
    * thunks) until `onStart` finishes, so [[resolveStartCallbacks]] can decide
    * how to schedule them. Asynchronously fired events bypass this, as they are never shared.
    */
  private val onStartCallbacks: JsArray[Transaction => Unit] = JsArray()

  override protected val config: Config = makeConfig(
    value => scheduleTransaction(fireValue(value, _)),
    err => scheduleTransaction(fireError(err, _)),
    () => startIndex,
    () => isStarted
  )

  override protected def onStart(): Unit = {
    onStartCallbacks.length = 0 // should already be empty, but just in case
    super.onStart() // runs config.onStart(), which may fill onStartCallbacks
    resolveStartCallbacks()
  }

  /** The source's ingress: the fire callbacks may be called from any thread, so the emission is delivered through the
    * runtime (`Runtime.deliver`). Inside the runtime's turn, which is where a start callback fires
    * synchronously, it is applied at once; from another thread it reaches a scheduled runtime as a turn of its own.
    */
  private def scheduleTransaction(fire: Transaction => Unit): Unit = {
    runtime.deliver(emitOrCollect(fire))
  }

  /** Inside a shared start, collect the emission for [[resolveStartCallbacks]];
    * otherwise fire it in a new transaction right away, as usual.
    */
  private def emitOrCollect(fire: Transaction => Unit): Unit = {
    if (!collectDuringSharedStart(fire)) {
      Transaction(runtime)(fire)
    }
  }

  /** Collect `fire` for the shared start's resolution when one is running; false when it is not and `fire` must run in a
    * transaction of its own. The decision reads the runtime's shared-start state, so it enters the runtime.
    */
  private def collectDuringSharedStart(fire: Transaction => Unit): Boolean = runtime.run {
    val collecting = runtime.sharedStart.isInSharedStart && runtime.trxOnStartFix144
    if (collecting) {
      onStartCallbacks.push(fire)
    }
    collecting
  }

  /** Schedule the events emitted synchronously during `onStart`:
    *  - If exactly one event: emit it via the shared-start batch
    *    (`Transaction.onStart.add`), so it shares a transaction with siblings
    *    started in the same `onStart.shared` block (e.g. a Laminar mount), like
    *    `signal.updates` does. This lets `merge` / `combine` order simultaneous
    *    start-emissions deterministically, rather than by start order.
    *  - More than one: emit each in its own transaction (as before). An
    *    observable can't emit twice per transaction, and batching only the first
    *    would let the rest overtake it through a deferring `merge`.
    *
    * Gated behind [[FeatureFlags.V18_TRX_ONSTART_FIX_144]]; when off,
    * onStartCallbacks is always empty and this is a no-op.
    *
    * See https://github.com/raquo/Airstream/issues/144
    */
  private def resolveStartCallbacks(): Unit = {
    val numEvents = onStartCallbacks.length
    if (numEvents == 1) {
      val fire = onStartCallbacks(0)
      onStartCallbacks.length = 0
      runtime.sharedStart.add { trx =>
        if (isStarted) {
          fire(trx)
        }
      }
    } else if (numEvents > 1) {
      var i = 0
      while (i < numEvents) {
        val fire = onStartCallbacks(i)
        Transaction(runtime)(fire)
        i += 1
      }
      onStartCallbacks.length = 0
    }
  }
}

object CustomStreamSource {

  @deprecated("Use EventStream.fromCustomSource", "15.0.0-M1")
  def apply[A](
    config: (FireValue[A], FireError, GetStartIndex, GetIsStarted) => Config
  ): EventStream[A] = {
    new CustomStreamSource[A](config)
  }
}
