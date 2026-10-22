package spoonbill.server

import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import spoonbill.effect.Effect

/** Absolute assembly deadline; progress and control frames cannot extend it. */
private[server] final class MessageDeadline[F[_]: Effect](timeout: FiniteDuration, shutdown: () => F[Unit])(
  implicit ec: ExecutionContext
) {
  private var generation = 0L
  private var pending: Option[ScheduledFuture[?]] = None
  private var closed = false

  def start(): Unit = synchronized {
    if (!closed && pending.isEmpty) {
      generation += 1
      val ticket = generation
      pending = Some(MessageDeadline.timer.schedule(new Runnable {
        def run(): Unit = {
          val expired = MessageDeadline.this.synchronized {
            if (!closed && generation == ticket && pending.nonEmpty) {
              closed = true
              pending = None
              true
            } else false
          }
          if (expired) ec.execute(new Runnable {
            def run(): Unit = Effect[F].runAsync(shutdown())(_ => ())
          })
        }
      }, timeout.toNanos, TimeUnit.NANOSECONDS))
    }
  }

  def complete(): Unit = synchronized {
    generation += 1
    pending.foreach(_.cancel(false))
    pending = None
  }

  def close(): Unit = synchronized {
    closed = true
    complete()
  }
}

private[server] object MessageDeadline {
  private val timer = new ScheduledThreadPoolExecutor(1, new ThreadFactory {
    def newThread(runnable: Runnable): Thread = {
      val thread = new Thread(runnable, "spoonbill-websocket-message")
      thread.setDaemon(true)
      thread
    }
  })
  timer.setRemoveOnCancelPolicy(true)
}
