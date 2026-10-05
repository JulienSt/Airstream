package com.raquo.airstream.platform

/** The execution permit of one runtime, on a platform with a single thread.
  *
  * There is no foreign thread to turn away, so the permit only remembers whether it is held. That is still needed: the
  * runtime uses it to tell a nested entry (run the body directly) from an outermost one (install the context, run, and
  * restore it in `finally`), and `Runtime.isEntered` reads it. Binding to a thread is a no-op here, which is why the two
  * factories build the same permit.
  */
final class RuntimePermit private () {

  private var held: Boolean = false

  /** True while the permit is held; the only thread there is holds it. */
  def isHeldByCurrentThread: Boolean = held

  /** True while the permit is held. */
  def isHeld: Boolean = held

  /** True when the permit can be acquired right now. */
  def mayAcquire: Boolean = !held

  /** Take the permit; false only when it is already held. */
  def acquire(): Boolean = {
    if (held) {
      false
    } else {
      held = true
      true
    }
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
  def floating(): RuntimePermit = new RuntimePermit()

  /** The single-thread permit; floating and bound are the same thing here. */
  def boundToCurrentThread(): RuntimePermit = new RuntimePermit()
}
