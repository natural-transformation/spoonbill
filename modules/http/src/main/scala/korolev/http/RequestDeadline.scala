package spoonbill.http

import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit}
import scala.concurrent.duration.FiniteDuration

/** Absolute HTTP header/body assembly deadline, with transport-only expiration. */
private[http] final class RequestDeadline(
  timeout: FiniteDuration,
  shutdown: () => Unit,
  clockNanos: () => Long = () => System.nanoTime()
) {
  private var generation = 0L
  private var pending: Option[ScheduledFuture[?]] = None
  private var closed = false
  private var startedAt = 0L

  def start(): Unit = synchronized {
    if (!closed && pending.isEmpty) {
      generation += 1
      startedAt = clockNanos()
      val ticket = generation
      pending = Some(RequestDeadline.timer.schedule(new Runnable {
        def run(): Unit = {
          val expired = RequestDeadline.this.synchronized {
            if (!closed && generation == ticket && pending.nonEmpty) {
              closed = true
              pending = None
              true
            } else false
          }
          // Physical shutdown must not wait for the application executor.
          if (expired) shutdown()
        }
      }, timeout.toNanos, TimeUnit.NANOSECONDS))
    }
  }

  def complete(): Boolean = synchronized {
    // A delayed scheduler must not admit a header whose time already elapsed.
    val timely = !closed && pending.nonEmpty && clockNanos() - startedAt < timeout.toNanos
    if (!timely) closed = true
    generation += 1
    pending.foreach(_.cancel(false))
    pending = None
    timely
  }

  def close(): Unit = synchronized {
    closed = true
    complete(): Unit
  }
}

private[http] object RequestDeadline {
  private val timer = new ScheduledThreadPoolExecutor(1, new ThreadFactory {
    def newThread(runnable: Runnable): Thread = {
      val thread = new Thread(runnable, "spoonbill-http-assembly")
      thread.setDaemon(true)
      thread
    }
  })
  timer.setRemoveOnCancelPolicy(true)
}
