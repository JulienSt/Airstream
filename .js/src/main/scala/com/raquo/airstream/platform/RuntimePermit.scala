package com.raquo.airstream.platform

/** The execution permit of a runtime a factory created (`Runtime.confined`, `scheduled`, `dispatched`), on a platform with a
  * single thread. The default runtime has a lighter one, `DefaultPermit`.
  *
  * There is no foreign thread to turn away, so the permit only remembers whether it is held. That is still needed: the
  * runtime uses it to tell a nested entry (run the body directly) from an outermost one (install the context, run, and
  * restore it in `finally`), and `Runtime.isEntered` reads it. Binding to a thread is a no-op here, so a confined and a
  * dispatched runtime build the same permit. A scheduled permit admits no synchronous entry: the graph is reached through
  * `submit`, and the mailbox's drainer claims the permit for each turn.
  */
final private[airstream] class RuntimePermit private (admitsEntry: Boolean) {

  private var held: Boolean = false

  /** Set once by the runtime's close: from then on nothing takes the permit. */
  private var retired: Boolean = false

  /** True while the permit is held; the only thread there is holds it. */
  def isHeldByCurrentThread: Boolean = held

  /** True while the permit is held. */
  def isHeld: Boolean = held

  /** True when a synchronous entry can take the permit right now. */
  def mayAcquire: Boolean = !retired && admitsEntry && !held

  /** Take the permit for a synchronous entry; false when it is already held or the permit is scheduled. */
  def acquire(): Boolean = admitsEntry && claim()

  /** Take the permit for a drainer's turn; false only when it is already held. */
  def claim(): Boolean = {
    if (held || retired) {
      false
    } else {
      held = true
      true
    }
  }

  /** Refuse every later `acquire` and `claim`; a current holder keeps the permit until it releases it. */
  def retire(): Unit = {
    retired = true
  }

  /** Give the permit back. */
  def release(): Unit = {
    held = false
  }

  /** No thread binding exists on this platform. */
  def boundThreadName: Option[String] = None

  /** The single thread's name, while the permit is held. */
  def holderName: Option[String] = if (held) Some("main") else None
}

private[airstream] object RuntimePermit {

  /** The single-thread permit; binding to the one thread there is changes nothing. */
  def boundToCurrentThread(): RuntimePermit = new RuntimePermit(admitsEntry = true)

  /** A permit only the mailbox's drainer claims; a synchronous entry is rejected. */
  def scheduled(): RuntimePermit = new RuntimePermit(admitsEntry = false)
}
