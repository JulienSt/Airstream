package com.raquo.airstream.fixtures

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/** A pool of `threads` named daemon threads taking tasks from one queue: the executor the real-thread specs hand to
  * scheduled runtimes. Its threads are named so a spec can tell which worker ran a turn, and it is closed explicitly so
  * a spec leaves no thread behind. A task that throws is reported, never allowed to kill its worker.
  */
final class ThreadPool(name: String, threads: Int) extends ExecutionContext {

  private val queue = new LinkedBlockingQueue[Runnable]()

  private val running = new AtomicBoolean(true)

  @volatile var reported: List[Throwable] = Nil

  private val workers: Seq[Thread] = (1 to threads).map { index =>
    val thread = new Thread(() => loop(), s"$name-$index")
    thread.setDaemon(true)
    thread.start()
    thread
  }

  /** The names of this pool's threads. */
  def threadNames: Set[String] = workers.map(_.getName).toSet

  override def execute(task: Runnable): Unit = queue.put(task)

  override def reportFailure(cause: Throwable): Unit = {
    reported = cause :: reported
  }

  /** Stop the workers once they finish their current task. */
  def close(): Unit = running.set(false)

  private def loop(): Unit = {
    while (running.get()) {
      val task = queue.poll(10, TimeUnit.MILLISECONDS)
      if (task ne null) {
        try {
          task.run()
        } catch {
          case NonFatal(err) => reportFailure(err)
        }
      }
    }
  }
}
