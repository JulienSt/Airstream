package com.raquo.airstream.core

/** A runtime engine's growable stack of transaction references. Pop clears the consumed slot so capacity does not retain
  * completed graphs. Each buffer belongs to one engine and is accessed only under that runtime's permit.
  */
final private[airstream] class TransactionStack {

  private var values = new Array[Transaction](4)

  private var count = 0

  def nonEmpty: Boolean = count != 0

  def push(transaction: Transaction): Unit = {
    if (count == values.length) {
      val expanded = new Array[Transaction](values.length * 2)
      Array.copy(values, 0, expanded, 0, count)
      values = expanded
    }
    values(count) = transaction
    count += 1
  }

  def pop(): Transaction = {
    if (count == 0) {
      throw new IllegalStateException("Cannot pop an empty transaction stack")
    }
    count -= 1
    val transaction = values(count)
    values(count) = null
    transaction
  }

  def clear(): Unit = {
    while (count > 0) {
      count -= 1
      values(count) = null
    }
  }
}
