package spoonbill.http

import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import spoonbill.effect.DeadlineMachine

/**
 * Absolute HTTP header/body assembly deadline, with transport-only expiration.
 */
private[http] final class RequestDeadline(
  timeout: FiniteDuration,
  shutdown: () => Unit,
  clockNanos: () => Long = () => System.nanoTime()
) {
  import DeadlineMachine.{Command, Event}
  private case class Held(machine: DeadlineMachine.State, pending: Option[ScheduledFuture[?]])
  private var held = Held(DeadlineMachine.State(timeout.toNanos), None)

  // Scheduling/cancellation and publication share this monitor. Retrying either
  // side effect in a CAS change function could install or cancel a job twice.
  private def advance(event: Event): Vector[Command] = {
    val (next, commands) = DeadlineMachine.step(held.machine, event)
    held = held.copy(machine = next)
    commands
  }

  def start(): Unit = synchronized {
    if (DeadlineMachine.canStart(held.machine)) {
      advance(Event.Start(clockNanos())).foreach {
        case Command.Schedule(ticket, delayNanos) =>
          try {
            val job = RequestDeadline.timer.schedule(
              new Runnable {
                def run(): Unit = {
                  val expired = RequestDeadline.this.synchronized {
                    val expire = advance(Event.Fired(ticket)).contains(Command.Expire)
                    if (expire) held = held.copy(pending = None)
                    expire
                  }
                  // Physical shutdown must not wait for the application executor.
                  if (expired) shutdown()
                }
              },
              delayNanos,
              TimeUnit.NANOSECONDS
            )
            held = held.copy(pending = Some(job))
          } catch {
            case NonFatal(error) =>
              advance(Event.ScheduleFailed(ticket))
              throw error
          }
        case _ => ()
      }
    }
  }

  private def clear(commands: Vector[Command]): Unit = {
    if (commands.contains(Command.Cancel)) held.pending.foreach(_.cancel(false))
    held = held.copy(pending = None)
  }

  private def completeLocked(): Boolean = {
    // Do not consult the clock for a deadline that was never started or closed.
    val now      = if (DeadlineMachine.isArmed(held.machine)) clockNanos() else 0L
    val commands = advance(Event.Complete(now))
    clear(commands)
    commands.contains(Command.Completed(true))
  }

  def complete(): Boolean = synchronized {
    completeLocked()
  }

  def close(): Unit = synchronized {
    clear(advance(Event.Close))
  }
}

private[http] object RequestDeadline {
  private val timer = new ScheduledThreadPoolExecutor(
    1,
    new ThreadFactory {
      def newThread(runnable: Runnable): Thread = {
        val thread = new Thread(runnable, "spoonbill-http-assembly")
        thread.setDaemon(true)
        thread
      }
    }
  )
  timer.setRemoveOnCancelPolicy(true)
}
