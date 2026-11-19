package spoonbill.zio

import spoonbill.effect.{Effect as SpoonbillEffect, Stream as SpoonbillStream}
import zio._
import zio.stream.ZStream

package object streams {

  implicit class SpoonbillStreamOps[R, O](stream: SpoonbillStream[RIO[R, *], O]) {

    def toZStream: ZStream[R, Throwable, O] =
      ZStream.unfoldZIO(()) { _ =>
        stream
          .pull()
          .map(mv => mv.map(v => (v, ())))
      }
  }

  private type Finalizer = Exit[Any, Any] => UIO[Unit]

  /**
   * The fiber of the pull that the bridge runs. It is known once it is forked.
   */
  private type Active = Promise[Nothing, Fiber[Any, Any]]

  /** How far a converted stream has come. The bridge keeps it in one cell. */
  private[streams] sealed trait Life
  private[streams] object Life {

    /** Open, and no pull is under way. */
    case object Idle extends Life

    /** Open, and the bridge runs one pull. */
    final case class Pulling(active: Active) extends Life

    /**
     * Over. Without a cause the stream ended or was cancelled. With a cause it
     * failed or died, and every later pull gets that cause. `closed` is done
     * when the scope has been closed.
     */
    final case class Over(cause: Option[Cause[Throwable]], closed: Promise[Nothing, Unit]) extends Life
  }

  /** What a pull is told when it asks to go on. */
  private sealed trait Admission
  private object Admission {
    case object Run                                          extends Admission
    final case class Wait(other: Active)                     extends Admission
    final case class Answer(cause: Option[Cause[Throwable]]) extends Admission
  }

  implicit class ZStreamOps[R, O](stream: ZStream[R, Throwable, O]) {

    /**
     * Pulls the stream chunk by chunk. The result ends when the stream ends.
     * When the stream fails, that `pull` and every later one fail with the
     * error of the stream.
     *
     * The stream keeps what it owns until it ends or fails, or until the result
     * is cancelled. `cancel` admits no new pull, interrupts the pull that is
     * under way, and releases what the stream owns when that pull has unwound.
     * After it a `pull` ends with `None`.
     *
     * The release is as prompt as ZIO interruption is. A pull that cannot be
     * interrupted, or a slow finalizer, delays it.
     */
    def toSpoonbill(implicit eff: SpoonbillEffect[RIO[R, *]]): RIO[R, ZSpoonbillStream[R, O]] =
      ZIO.uninterruptibleMask { restore =>
        for {
          scope <- Scope.make
          // The scope stays open for the pulls. It is closed here only when the pull cannot be made.
          pull <- restore(scope.extend[R](stream.toPull)).onError(cause => scope.close(Exit.failCause(cause)))
          life <- Ref.make[Life](Life.Idle)
        } yield ZSpoonbillStream[R, O](pull, scope.close(_), life)
      }
  }

  private[streams] case class ZSpoonbillStream[R, O](
    zPull: ZIO[R, Option[Throwable], Chunk[O]],
    finalizer: Finalizer,
    life: Ref[Life]
  )(implicit eff: SpoonbillEffect[RIO[R, *]])
      extends SpoonbillStream[RIO[R, *], Seq[O]] {

    def pull(): RIO[R, Option[Seq[O]]] =
      ZIO.uninterruptibleMask(restore => admit(restore))

    /**
     * Stops new pulls, interrupts the pull that is under way and closes the
     * scope when that pull has unwound. It returns when the scope is closed.
     */
    def cancel(): RIO[R, Unit] =
      ZIO.uninterruptibleMask { restore =>
        Promise.make[Nothing, Unit].flatMap { closed =>
          life.modify {
            case Life.Idle            => ((true, Option.empty[Active], closed), Life.Over(None, closed))
            case Life.Pulling(active) => ((true, Option(active), closed), Life.Over(None, closed))
            // A stream that failed forgets its failure here: after `cancel` a pull gets the end.
            case over: Life.Over => ((false, Option.empty[Active], over.closed), Life.Over(None, over.closed))
          }.flatMap { case (first, active, done) =>
            // The closing runs by itself. A canceller that is interrupted leaves nothing half done.
            val closing = ZIO.foreachDiscard(active.toList)(_.await.flatMap(_.interrupt)) *> close(Exit.unit, done)
            ZIO.when(first)(closing.forkDaemon) *> restore(done.await)
          }
        }
      }

    // Only an idle stream admits a pull. A second pull waits for the first, and a
    // stream that is over answers at once.
    private def admit(restore: ZIO.InterruptibilityRestorer): RIO[R, Option[Seq[O]]] =
      Promise.make[Nothing, Fiber[Any, Any]].flatMap { active =>
        life.modify {
          case Life.Idle          => (Admission.Run, Life.Pulling(active))
          case busy: Life.Pulling => (Admission.Wait(busy.active), busy)
          case over: Life.Over    => (Admission.Answer(over.cause), over)
        }.flatMap {
          case Admission.Run           => run(restore, active)
          case Admission.Wait(other)   => restore(other.await.flatMap(_.await)) *> ZIO.yieldNow *> admit(restore)
          case Admission.Answer(cause) => answer(cause)
        }
      }

    // The bridge owns the pull: it runs in a fiber of its own, so that `cancel`
    // can interrupt it without interrupting the consumer. The fiber starts with
    // the consumer's context, and what it changes there comes back with a value.
    private def run(restore: ZIO.InterruptibilityRestorer, active: Active): RIO[R, Option[Seq[O]]] =
      for {
        // In the error channel `None` is the end of the stream and `Some` is its
        // failure. Only the end becomes a value; the failure stays a failure.
        fiber <- zPull.unsome.interruptible.fork
        _     <- active.succeed(fiber)
        exit  <- restore(fiber.await).onInterrupt(abandon(active, fiber))
        out   <- settle(active, fiber, exit)
      } yield out

    // The consumer was interrupted while it waited. Its pull goes with it, and the
    // stream stays open.
    private def abandon(active: Active, fiber: Fiber[Any, Any]): UIO[Unit] =
      fiber.interrupt *> life.update {
        case Life.Pulling(mine) if mine eq active => Life.Idle
        case other                                => other
      }

    private def settle(
      active: Active,
      fiber: Fiber.Runtime[Throwable, Option[Chunk[O]]],
      exit: Exit[Throwable, Option[Chunk[O]]]
    ): RIO[R, Option[Seq[O]]] = exit match {
      case Exit.Success(chunk @ Some(_)) =>
        leave(active, Life.Idle).flatMap {
          case None        => fiber.inheritAll.as(chunk)
          case Some(cause) => answer(cause)
        }
      case Exit.Success(None) =>
        Promise.make[Nothing, Unit].flatMap { closed =>
          leave(active, Life.Over(None, closed)).flatMap {
            case None        => fiber.inheritAll *> close(Exit.unit, closed) *> ZIO.none
            case Some(cause) => answer(cause)
          }
        }
      case Exit.Failure(cause) =>
        Promise.make[Nothing, Unit].flatMap { closed =>
          leave(active, Life.Over(Some(cause), closed)).flatMap {
            case None       => close(Exit.failCause(cause), closed) *> ZIO.failCause(cause)
            case Some(over) => answer(over)
          }
        }
    }

    // Gives the pull of `active` up for `next`. When the stream is over meanwhile,
    // because it was cancelled, the result is what such a stream answers.
    private def leave(active: Active, next: Life): UIO[Option[Option[Cause[Throwable]]]] =
      life.modify {
        case Life.Pulling(mine) if mine eq active => (None, next)
        case over: Life.Over                      => (Some(over.cause), over)
        case other                                => (Some(None), other)
      }

    // Closes the scope and says so, also when a finalizer dies.
    private def close(exit: Exit[Any, Any], closed: Promise[Nothing, Unit]): UIO[Unit] =
      finalizer(exit).ensuring(closed.succeed(()))

    private def answer(cause: Option[Cause[Throwable]]): RIO[R, Option[Seq[O]]] =
      cause.fold[RIO[R, Option[Seq[O]]]](ZIO.none)(ZIO.failCause(_))
  }
}
