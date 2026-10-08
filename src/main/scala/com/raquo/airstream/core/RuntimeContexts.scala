package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.CrossRuntimeEdge
import com.raquo.airstream.platform.ThreadSlot

/** What a program knows about the runtimes other than the default one: the part of the entry protocol that exists only because a
  * second runtime can exist.
  *
  * Until the first `Runtime.confined`, `Runtime.scheduled` or `Runtime.dispatched` call the default runtime is the only one, so
  * no thread can be inside another runtime's turn and every edge joins the default runtime to itself: [[RuntimeContexts.Single]]
  * answers both questions without looking anything up. The first factory call switches the program to
  * [[RuntimeContexts.PerThread]] before it constructs the second runtime, and every non-default runtime installs itself in the
  * calling thread's slot for its turns from then on.
  *
  * The default runtime never installs itself in that slot. Whether a thread is inside the default runtime is its own permit's
  * answer, which the default runtime keeps in every program; so the switch may happen in the middle of a default turn, and the
  * runtime created there is still refused an entry from that turn.
  *
  * A program that never calls a factory never reaches `PerThread`: the whole-program linkers of Scala.js and Scala Native then see
  * one implementation, call it directly, inline its constant answers and drop the slot; on the JVM the JIT does the same while the
  * second class is not loaded.
  */
sealed abstract private[airstream] class RuntimeContexts {

  /** The non-default runtime whose turn the calling thread is inside, or null. */
  def activeOther: Runtime

  /** True once a factory has published the context needed by more than one runtime identity. */
  def checksEdges: Boolean

  /** Reject an edge between `expected` and `actual` unless they are the same runtime.
    *
    * @throws CrossRuntimeEdge when they differ
    */
  def requireSame(expected: Runtime, actual: Runtime): Unit
}

private[airstream] object RuntimeContexts {

  /** The program has created no runtime besides the default one: every thread is inside the default runtime or inside none, and
    * every runtime an edge can join is the default runtime.
    */
  object Single extends RuntimeContexts {

    override def activeOther: Runtime = null

    override def checksEdges: Boolean = false

    override def requireSame(expected: Runtime, actual: Runtime): Unit = ()
  }

  /** The program has created a runtime besides the default one: each thread's slot holds the non-default runtime whose turn it
    * is inside. Thread-local storage on JVM and Native, a plain slot on Scala.js, where there is one thread.
    */
  object PerThread extends RuntimeContexts {

    private val slot: ThreadSlot[Runtime] = new ThreadSlot[Runtime]

    /** The calling thread's cell, for an entry that checks, installs and clears it with one lookup. */
    def cell: ThreadSlot.Cell[Runtime] = slot.cell

    override def activeOther: Runtime = slot.get

    override def checksEdges: Boolean = true

    override def requireSame(expected: Runtime, actual: Runtime): Unit = {
      if (expected ne actual) {
        throw CrossRuntimeEdge(Seq(expected, actual))
      }
    }
  }
}
