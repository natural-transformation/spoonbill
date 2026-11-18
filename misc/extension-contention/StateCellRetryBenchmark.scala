package spoonbill.internal

import java.util.concurrent.{CountDownLatch, Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import spoonbill.effect.StateCell

/** Diagnostic only: counts invocations around the real pure transition and StateCell retry loop.
  * It omits handlers/effect scheduling, so its retries are NOT attributed to the runtime benchmark.
  */
object StateCellRetryBenchmark {
  import ExtensionMachine.*
  def main(args: Array[String]): Unit = {
    val Array(writersArg, extensionsArg, windowArg, countArg) = args
    val writers = writersArg.toInt
    val extensions = extensionsArg.toInt
    val window = windowArg.toInt
    val perWriter = countArg.toInt
    require(writers > 0 && extensions > 0 && window > 0 && perWriter > 0)
    val registered = (0 until extensions).foldLeft(initial[Int, Unit](false)) { (state, index) =>
      step(state, Event.Register(s"observer-$index"))._1
    }
    val cell = new StateCell(step(registered, Event.Begin)._1)
    val permits = new Semaphore(window)
    val available = new Semaphore(0)
    val started = new CountDownLatch(1)
    val finished = new CountDownLatch(writers + 1)
    val error = new AtomicReference[Throwable]()
    val attempts = new Array[Long](writers + 1)
    val commits = new Array[Long](writers + 1)
    def advance(thread: Int, event: Event[Int, Unit]): Vector[Command[Int, Unit]] = {
      val commands = cell.modify { state =>
        // Intentional measurement side effect, in this diagnostic only. Each
        // counter has one owner, and it never affects the returned transition.
        attempts(thread) += 1L
        step(state, event)
      }
      commits(thread) += 1L
      commands
    }
    def guarded(operation: => Unit): Runnable = () => {
      try { require(started.await(5, TimeUnit.SECONDS)); operation }
      catch { case cause: Throwable => error.compareAndSet(null, cause); () }
      finally finished.countDown()
    }
    val producers = (0 until writers).map { writer => new Thread(guarded {
      (0 until perWriter).foreach { index =>
        require(permits.tryAcquire(30, TimeUnit.SECONDS))
        advance(writer, Event.Written(index))
        available.release()
      }
    }) }
    val consumer = new Thread(guarded {
      (0 until writers * perWriter).foreach { _ =>
        require(available.tryAcquire(30, TimeUnit.SECONDS))
        (0 to extensions).foreach { _ =>
          val item = advance(writers, Event.Next).collectFirst { case Command.Hand(value) => value }.get
          advance(writers, Event.Start(item))
        }
        permits.release()
      }
    })
    producers.foreach(_.start()); consumer.start(); started.countDown()
    require(finished.await(90, TimeUnit.SECONDS), "Retry diagnostic did not finish")
    producers.foreach(_.join()); consumer.join()
    Option(error.get()).foreach(cause => throw cause)
    require(waiting(cell.get) == 0)
    val expected = writers.toLong * perWriter * (1L + 2L * (extensions + 1L))
    require(commits.sum == expected)
    println(s"""{"writers":$writers,"extensions":$extensions,"window":$window,"notifications":${writers * perWriter},"commits":${commits.sum},"attempts":${attempts.sum},"casRetries":${attempts.sum - commits.sum},"retriesPerCommit":${(attempts.sum - commits.sum).toDouble / commits.sum},"writerRetries":${attempts.take(writers).sum - commits.take(writers).sum},"consumerRetries":${attempts.last - commits.last},"correct":true,"scope":"machine-and-cell diagnostic, excludes handlers and scheduling"}""")
  }
}
