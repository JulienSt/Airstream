package com.raquo.airstream.platform

/** The execution permit of one runtime, on a platform with a single thread.
  *
  * There is no foreign thread to turn away, so the permit only remembers whether it is held. That is still needed: the
  * runtime uses it to tell a nested entry (run the body directly) from an outermost one (install the context, run, and
  * restore it in `finally`), and `Runtime.isEntered` reads it. Binding to a thread is a no-op here, which is why the two
  * direct factories build the same permit. A scheduled permit admits no synchronous entry: the graph is reached through
  * `submit`, and the mailbox's drainer claims the permit for each turn.
  */
final class RuntimePermit private (admitsEntry: Boolean) {

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

object RuntimePermit {

  /** The single-thread permit; floating and bound are the same thing here. */
  def floating(): RuntimePermit = new RuntimePermit(admitsEntry = true)

  /** The single-thread permit; floating and bound are the same thing here. */
  def boundToCurrentThread(): RuntimePermit = new RuntimePermit(admitsEntry = true)

  /** A permit only the mailbox's drainer claims; a synchronous entry is rejected. */
  def scheduled(): RuntimePermit = new RuntimePermit(admitsEntry = false)
}
