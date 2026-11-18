package spoonbill.zio.http

import scala.concurrent.duration.FiniteDuration
import spoonbill.internal.SetupOwnershipMachine
import zio.{Duration, Fiber, RIO, ZIO}

/** Owns application resources until the HTTP server starts the socket handler. */
private[http] final class WebSocketSetup[R] private (cancelInput: RIO[R, Unit]) {
  import SetupOwnershipMachine.{Command, Event, State}
  private type Release = () => RIO[R, Unit]
  private final case class Held(state: State, prepared: Option[Release])
  private var held = Held(State.Waiting, None)

  /** Called under this ownership monitor; response handles follow the pure transition. */
  private def advance(event: Event, incoming: Option[Release] = None): (Vector[Command], Option[Release]) = {
    val before = held
    val (next, commands) = SetupOwnershipMachine.step(before.state, event)
    val prepared =
      if (commands.contains(Command.RetainPrepared)) incoming
      else if (next == State.Prepared) before.prepared
      else None
    held = Held(next, prepared)
    (commands, before.prepared)
  }
  // Initialized before this object is exposed by make. The timer only calls expire.
  private var deadline: Fiber.Runtime[Nothing, Unit] = _

  def prepare(release: () => RIO[R, Unit]): RIO[R, Boolean] = ZIO.uninterruptible {
    ZIO.succeed {
      synchronized {
        advance(Event.Prepare, Some(release))._1.contains(Command.RetainPrepared)
      }
    }.flatMap {
      case true => ZIO.succeed(true)
      case false => ZIO.suspendSucceed(release()).ignore.as(false)
    }
  }

  def attach: RIO[R, Boolean] = ZIO.uninterruptible {
    ZIO.succeed {
      synchronized {
        advance(Event.Attach)._1.contains(Command.Transfer)
      }
    }.flatMap {
      case true => deadline.interrupt.as(true)
      case false => ZIO.succeed(false)
    }
  }

  private def expire: RIO[R, Unit] = ZIO.uninterruptible {
    ZIO.succeed {
      synchronized {
        val (commands, prepared) = advance(Event.Abandon)
        commands.headOption match {
          case Some(Command.ReleaseInput) => Some(() => ZIO.unit)
          case Some(Command.ReleasePrepared) => prepared
          case _ => None
        }
      }
    }.flatMap {
      case Some(release) => cancelInput.ignore *> ZIO.suspendSucceed(release()).ignore
      case None => ZIO.unit
    }
  }

  def abandon: RIO[R, Unit] = expire *> deadline.interrupt.unit
}

private[http] object WebSocketSetup {
  def make[R](cancelInput: RIO[R, Unit], timeout: FiniteDuration): RIO[R, WebSocketSetup[R]] = ZIO.suspendSucceed {
    val setup = new WebSocketSetup[R](cancelInput)
    (ZIO.sleep(Duration.fromScala(timeout)) *> setup.expire.ignore).forkDaemon.map { fiber =>
      setup.deadline = fiber
      setup
    }
  }
}
