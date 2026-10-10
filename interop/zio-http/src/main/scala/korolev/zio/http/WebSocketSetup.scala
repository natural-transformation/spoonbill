package spoonbill.zio.http

import scala.concurrent.duration.FiniteDuration
import zio.{Duration, Fiber, RIO, ZIO}

/** Owns application resources until the HTTP server starts the socket handler. */
private[http] final class WebSocketSetup[R] private (cancelInput: RIO[R, Unit]) {
  private sealed trait State
  private case object Waiting extends State
  private final case class Prepared(release: () => RIO[R, Unit]) extends State
  private case object Attached extends State
  private case object Abandoned extends State

  private var state: State = Waiting
  // Initialized before this object is exposed by make. The timer only calls expire.
  private var deadline: Fiber.Runtime[Nothing, Unit] = _

  def prepare(release: () => RIO[R, Unit]): RIO[R, Boolean] = ZIO.uninterruptible {
    ZIO.succeed {
      synchronized {
        state match {
          case Waiting => state = Prepared(release); true
          case _ => false
        }
      }
    }.flatMap {
      case true => ZIO.succeed(true)
      case false => ZIO.suspendSucceed(release()).ignore.as(false)
    }
  }

  def attach: RIO[R, Boolean] = ZIO.uninterruptible {
    ZIO.succeed {
      synchronized {
        state match {
          case Prepared(_) => state = Attached; true
          case _ => false
        }
      }
    }.flatMap {
      case true => deadline.interrupt.as(true)
      case false => ZIO.succeed(false)
    }
  }

  private def expire: RIO[R, Unit] = ZIO.uninterruptible {
    ZIO.succeed {
      synchronized {
        state match {
          case Waiting => state = Abandoned; Some(() => ZIO.unit)
          case Prepared(release) => state = Abandoned; Some(release)
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
