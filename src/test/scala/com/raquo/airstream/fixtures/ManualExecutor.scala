package com.raquo.airstream.fixtures

import scala.collection.mutable
import scala.concurrent.ExecutionContext

/** An execution context the test pumps by hand: `execute` only queues the task and counts the call, `runAll` runs what is
  * queued. A scheduled runtime's drain protocol can then be driven step by step on any platform, with no thread and no
  * wait, and the number of drain tasks the runtime handed to its executor is a plain count.
  */
final class ManualExecutor extends ExecutionContext {

  private val queue: mutable.Queue[Runnable] = mutable.Queue.empty

  /** How many tasks were handed to this executor so far. */
  var executions: Int = 0

  /** How many tasks wait to be run. */
  def pending: Int = queue.size

  override def execute(task: Runnable): Unit = {
    executions += 1
    queue.enqueue(task)
  }

  override def reportFailure(cause: Throwable): Unit = throw cause

  /** Run the next queued task; false when none waits. */
  def runNext(): Boolean = {
    if (queue.isEmpty) {
      false
    } else {
      queue.dequeue().run()
      true
    }
  }

  /** Run queued tasks, including the ones they queue, until none waits. */
  def runAll(): Unit = {
    while (runNext()) {}
  }
}
