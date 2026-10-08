package com.raquo.airstream.core

import scala.scalajs.js

/** A runtime engine's LIFO on the JavaScript platform array. Pop and clear release consumed transaction references. */
final private[airstream] class TransactionStack {

  private val values = new js.Array[Transaction]

  def nonEmpty: Boolean = values.length != 0

  def push(transaction: Transaction): Unit = {
    values.push(transaction)
    ()
  }

  def pop(): Transaction = {
    if (!nonEmpty) {
      throw new IllegalStateException("Cannot pop an empty transaction stack")
    }
    values.pop()
  }

  def clear(): Unit = values.length = 0
}
