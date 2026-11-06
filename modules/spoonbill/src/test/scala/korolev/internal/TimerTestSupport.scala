package spoonbill.internal

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import spoonbill.effect.Reporter

private[spoonbill] object TimerTestSupport {
  final class Worker extends ExecutionContext {
    private val tasks                         = mutable.Queue.empty[Runnable]
    def execute(task: Runnable): Unit         = tasks.synchronized(tasks.enqueue(task))
    def reportFailure(error: Throwable): Unit = throw error
    def pending: Int                          = tasks.synchronized(tasks.size)
    def one(): Unit                           = tasks.synchronized(tasks.dequeue()).run()
    def drain(): Unit = {
      var count = 0
      while (pending > 0) { one(); count += 1; require(count < 10000, "Worker did not quiesce") }
    }
  }

  final class Deadlines extends Frontend.RpcDeadlineScheduler {
    var onCancel: () => Unit = () => ()
    final class Task(val duration: FiniteDuration, val wake: () => Unit) {
      var canceled     = false
      var fired        = false
      def fire(): Unit = { fired = true; wake() }
    }
    val tasks = mutable.ArrayBuffer.empty[Task]
    var now   = 0L
    def schedule(delay: FiniteDuration)(wake: () => Unit): () => Unit = {
      val task = new Task(delay, wake)
      tasks += task
      () => { task.canceled = true; onCancel() }
    }
    def active: Vector[Task]                    = tasks.filter(t => !t.canceled && !t.fired).toVector
    def advance(duration: FiniteDuration): Unit = now += duration.toNanos
    def fire(): Unit                            = active.head.fire()
  }

  final class Reports extends Reporter {
    var errors                                                        = Vector.empty[(String, Throwable)]
    var warnings                                                      = Vector.empty[String]
    def error(message: String, cause: Throwable): Unit                = errors :+= message -> cause
    def error(message: String): Unit                                  = errors :+= message -> null
    def warning(message: String, cause: Throwable): Unit              = warnings :+= message
    def warning(message: String): Unit                                = warnings :+= message
    def info(message: String): Unit                                   = ()
    def debug(message: String): Unit                                  = ()
    def debug(message: String, arg1: Any): Unit                       = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  }
}
