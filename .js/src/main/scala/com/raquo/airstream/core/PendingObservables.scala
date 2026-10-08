package com.raquo.airstream.core

import scala.scalajs.js

/** One runtime engine's stable rank queue. Its caller enqueues only absent nodes and drains it before running another
  * transaction. Ranks are read once on admission; equal ranks retain insertion order. Consumed slots release their graph
  * references, and clear resets every remaining membership flag after a failed turn.
  */
final private[airstream] class PendingObservables {

  private val nodes = new js.Array[SyncObservable[?]](4)
  private val ranks = new js.Array[Int](4)
  private var head = 0
  private var tail = 0

  def nonEmpty: Boolean = head != tail

  def contains(observable: SyncObservable[?]): Boolean = observable match {
    case state: PendingState => state.isPending
    case _ =>
      // Custom nodes compiled before PendingState keep their existing trait ABI and correct identity semantics.
      var index = head
      while (index < tail) {
        if (nodes(index) eq observable) return true
        index += 1
      }
      false
  }

  def enqueue(observable: SyncObservable[?]): Unit = {
    makeRoom()
    val rank = Protected.topoRank(observable)
    var index = tail
    while (index > head && ranks(index - 1) > rank) {
      nodes(index) = nodes(index - 1)
      ranks(index) = ranks(index - 1)
      index -= 1
    }
    nodes(index) = observable
    ranks(index) = rank
    tail += 1
    observable match {
      case state: PendingState => state.isPending = true
      case _ => ()
    }
  }

  def dequeue(): SyncObservable[?] = {
    if (!nonEmpty) throw new IllegalStateException("Cannot dequeue an empty pending queue")
    val observable = nodes(head)
    nodes(head) = null
    head += 1
    if (head == tail) {
      head = 0
      tail = 0
    }
    observable match {
      case state: PendingState => state.isPending = false
      case _ => ()
    }
    observable
  }

  def clear(): Unit = {
    while (nonEmpty) dequeue()
  }

  private def makeRoom(): Unit = {
    if (tail == nodes.length) {
      val remaining = tail - head
      if (head <= nodes.length / 2) {
        nodes.length = nodes.length * 2
        ranks.length = ranks.length * 2
      }
      if (head != 0) {
        var index = 0
        while (index < remaining) {
          nodes(index) = nodes(head + index)
          ranks(index) = ranks(head + index)
          index += 1
        }
        while (index < tail) {
          nodes(index) = null
          index += 1
        }
      }
      head = 0
      tail = remaining
    }
  }
}
