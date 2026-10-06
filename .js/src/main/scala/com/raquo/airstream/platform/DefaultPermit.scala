package com.raquo.airstream.platform

/** The default runtime's permit on a platform with a single thread: one flag that says whether the runtime is entered.
  *
  * No thread can be turned away here, so the flag does not exclude anything. It is kept because two things ask whether the
  * default runtime is entered: a runtime the program creates later refuses an entry from inside a default turn, even
  * from the very turn that created it, and `Runtime.isEntered` reports it. Writing one field on the way in and out is what that
  * costs; the binding, the scheduled permit and the per-thread context are not linked unless the program creates a runtime.
  */
final private[airstream] class DefaultPermit {

  private var entered: Boolean = false

  /** True while the runtime is entered: whenever it is entered, the single thread is the one inside. */
  def isHeldByCurrentThread: Boolean = entered

  /** True while the runtime is entered. */
  def isHeld: Boolean = entered

  /** Asked only by a thread that does not hold the permit, which on one thread means nobody holds it. */
  def mayAcquire: Boolean = true

  /** Take the permit and return `entry`; asked only while nobody holds it (see `mayAcquire`), so it always succeeds. Returning
    * the caller's token rather than a flag lets the optimizer see that the caller's refusal branch never runs.
    */
  @inline def acquireFor[E >: Null <: AnyRef](entry: E): E = {
    entered = true
    entry
  }

  /** Give the permit back. */
  @inline def release(): Unit = {
    entered = false
  }

  /** The single thread's name, while the runtime is entered. */
  def holderName: Option[String] = if (entered) Some("main") else None
}
