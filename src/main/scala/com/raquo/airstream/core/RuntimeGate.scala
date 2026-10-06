package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.{ClosedRuntimeEntry, CrossRuntimeAccess, ForeignThreadEntry, ScheduledRuntimeEntry}
import com.raquo.airstream.platform.{AtomicCell, DefaultPermit, RuntimePermit, ThreadSlot}

/** How one runtime admits the calling thread: its permit, the context its turns install, and what a refused thread is told.
  *
  * The default runtime and the runtimes the factories create need different amounts of machinery. The default runtime
  * keeps only what the single-runtime contract needs: whether it is entered, and on platforms with threads which thread holds it.
  * A runtime from `Runtime.confined`, `Runtime.scheduled` or `Runtime.dispatched` adds the thread binding or the scheduled
  * permit, the per-thread context that detects a cross-runtime entry, the mailbox and the lifecycle a close moves through.
  * [[DefaultGate]] is the first and [[ManagedGate]] the second, and only the factories construct a `ManagedGate`; a program that
  * never calls them has one instantiated gate class, so the linkers bind every call below statically, inline the default
  * gate's bodies into the runtime's entry, and drop the managed gate with everything only it reaches.
  *
  * Every method that takes the permit or explains a refusal is asked only when the calling thread does not hold the permit
  * already: the runtime runs a nested body directly, so only an outermost entry reaches the gate.
  */
sealed abstract private[airstream] class RuntimeGate {

  /** What an outermost entry hands to its exit, never null; null is the refusal. A managed runtime hands its context cell, so an
    * entry looks the thread-local up once; the default runtime installs no context and hands back its own gate.
    */
  type Entry >: Null <: AnyRef

  /** True when the calling thread holds the permit, which is what makes a nested entry free. */
  def isHeldByCurrentThread: Boolean

  /** True while some thread holds the permit. */
  def isHeld: Boolean

  /** The name of the thread that holds the permit, while one does. */
  def holderName: Option[String]

  /** The name of the thread the runtime is bound to, when it is bound. */
  def boundThreadName: Option[String]

  /** Take the permit for the calling thread's outermost synchronous entry and install the runtime as its context; null when the
    * thread may not enter now, which [[refusal]] then explains.
    */
  def tryEnter(runtime: Runtime): Entry

  /** Take the permit for a turn the calling thread runs for the runtime's mailbox, and install the context; null when refused. */
  def claim(runtime: Runtime): Entry

  /** Clear the context `entry` installed and give the permit back, on every exit of an outermost entry or turn. */
  def exit(entry: Entry): Unit

  /** Why [[tryEnter]] refused the calling thread just now. */
  def refusal(runtime: Runtime): AirstreamError

  /** Why the calling thread may not access the runtime's graph right now, without entering it; null when it may. */
  def accessRefusal(runtime: Runtime): AirstreamError

  /** The mailbox of a scheduled or dispatched runtime; null for a direct one, which runs a submitted command at once. */
  def mailbox: Mailbox

  /** Where the runtime is in its lifecycle. */
  def state: RuntimeState
}

/** The gate of the default runtime: its permit and nothing else.
  *
  * The methods an entry calls are `@inline`: when this is the only instantiated gate the Scala.js optimizer binds the runtime's
  * calls to them statically, but it inlines a statically bound method only when it is a plain accessor or marked so; inlined,
  * the constant answers of a program without other runtimes fold away and the runtime's entry is the permit's flag.
  *
  * It never installs a context. Whether a thread is inside the default runtime is the permit's answer, and whether it is inside
  * another runtime is the slot of [[RuntimeContexts.PerThread]], which only the other runtimes write; until the program creates
  * one, [[RuntimeContexts.Single]] answers that no thread is. On Scala.js the permit is one flag; on JVM and Native it records the
  * holding thread, taken with a compare-and-set wherever a second thread can exist.
  */
final private[airstream] class DefaultGate extends RuntimeGate {

  override type Entry = DefaultGate

  private val permit: DefaultPermit = new DefaultPermit

  /** Which runtimes besides the default one the program has: none until the first factory call, which switches this once, before
    * it constructs the second runtime. Volatile because the switching thread and the threads entering the default runtime differ.
    */
  @volatile private var others: RuntimeContexts = RuntimeContexts.Single

  /** The program's knowledge of the other runtimes; see [[RuntimeContexts]]. */
  @inline def contexts: RuntimeContexts = others

  /** A runtime besides the default one is about to exist: from now on the other runtimes' turns are tracked per thread. */
  def admitOtherRuntimes(): Unit = {
    others = RuntimeContexts.PerThread
  }

  @inline override def isHeldByCurrentThread: Boolean = permit.isHeldByCurrentThread

  override def isHeld: Boolean = permit.isHeld

  override def holderName: Option[String] = permit.holderName

  /** The default runtime floats: any thread may enter it while no other thread is inside. */
  override def boundThreadName: Option[String] = None

  /** Checked against the other runtimes first, so that a turn of another runtime never takes or waits on this permit. */
  @inline override def tryEnter(runtime: Runtime): DefaultGate = {
    if (others.activeOther ne null) null else claim(runtime)
  }

  @inline override def claim(runtime: Runtime): DefaultGate = permit.acquireFor(this)

  @inline override def exit(entry: DefaultGate): Unit = permit.release()

  override def refusal(runtime: Runtime): AirstreamError = {
    val other = others.activeOther
    if (other ne null) CrossRuntimeAccess(active = other, target = runtime) else ForeignThreadEntry(runtime)
  }

  override def accessRefusal(runtime: Runtime): AirstreamError = {
    val other = others.activeOther
    if (other ne null) {
      CrossRuntimeAccess(active = other, target = runtime)
    } else if (!permit.mayAcquire) {
      ForeignThreadEntry(runtime)
    } else {
      null
    }
  }

  @inline override def mailbox: Mailbox = null

  /** The default runtime cannot be closed and never fails. */
  @inline override def state: RuntimeState = RuntimeState.Open
}

/** The gate of a runtime a factory created: a thread-bound or scheduled permit, the context each turn installs in the calling
  * thread's slot, the mailbox of a scheduled or dispatched runtime, and the lifecycle of a direct one.
  *
  * A synchronous entry is refused while the calling thread is inside another runtime's turn: another managed runtime's, which
  * the slot shows, or the default runtime's, which the default permit shows.
  *
  * @param mailboxFor builds the mailbox of a scheduled or dispatched runtime, null for a confined one; passed in rather than
  *                   chosen by a constructor branch, so the mailbox, its promises and `scala.concurrent` stay out of programs that
  *                   create only confined runtimes
  */
final private[airstream] class ManagedGate(permit: RuntimePermit, mailboxFor: ManagedGate => Mailbox) extends RuntimeGate {

  override type Entry = ThreadSlot.Cell[Runtime]

  override val mailbox: Mailbox = if (mailboxFor eq null) null else mailboxFor(this)

  /** The lifecycle of a direct runtime; a scheduled runtime's lives in its mailbox. */
  private val directState: AtomicCell[RuntimeState] = new AtomicCell[RuntimeState](RuntimeState.Open)

  override def state: RuntimeState = if (mailbox eq null) directState.get else mailbox.state

  /** Move a direct runtime's lifecycle on, during its close. */
  def advanceDirectState(next: RuntimeState): Unit = directState.set(next)

  /** Refuse every later entry and claim, during the runtime's close; a current holder keeps the permit until it exits. */
  def retire(): Unit = permit.retire()

  override def isHeldByCurrentThread: Boolean = permit.isHeldByCurrentThread

  override def isHeld: Boolean = permit.isHeld

  override def holderName: Option[String] = permit.holderName

  override def boundThreadName: Option[String] = permit.boundThreadName

  override def tryEnter(runtime: Runtime): ThreadSlot.Cell[Runtime] = {
    val cell = RuntimeContexts.PerThread.cell
    if ((cell.value ne null) || Runtime.isInDefaultTurn || !permit.acquire()) {
      null
    } else {
      cell.value = runtime
      cell
    }
  }

  override def claim(runtime: Runtime): ThreadSlot.Cell[Runtime] = {
    val cell = RuntimeContexts.PerThread.cell
    if (permit.claim()) {
      cell.value = runtime
      cell
    } else {
      null
    }
  }

  override def exit(entry: ThreadSlot.Cell[Runtime]): Unit = {
    entry.value = null
    permit.release()
  }

  override def refusal(runtime: Runtime): AirstreamError = {
    val active = Runtime.activeTurn
    if (active ne null) CrossRuntimeAccess(active = active, target = runtime) else entryRejection(runtime)
  }

  override def accessRefusal(runtime: Runtime): AirstreamError = {
    val active = Runtime.activeTurn
    if (active ne null) {
      CrossRuntimeAccess(active = active, target = runtime)
    } else if (!permit.mayAcquire) {
      entryRejection(runtime)
    } else {
      null
    }
  }

  /** Why the permit refused a thread that is inside no turn: a scheduled runtime is entered through `submit`, a closed one not
    * at all, a direct one belongs to another thread right now.
    */
  private def entryRejection(runtime: Runtime): AirstreamError = {
    if (state eq RuntimeState.Closed) ClosedRuntimeEntry(runtime)
    else if (mailbox ne null) ScheduledRuntimeEntry(runtime)
    else ForeignThreadEntry(runtime)
  }
}
