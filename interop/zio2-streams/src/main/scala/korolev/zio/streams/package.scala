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
     * failed or died. `closed` ends as the closing of the scope ended, so a
     * defect of a finalizer is in it. A pull answers with both.
     */
    final case class Over(cause: Option[Cause[Throwable]], closed: Promise[Nothing, Unit]) extends Life
  }

  /** What a pull is told when it asks to go on. */
  private sealed trait Admission
  private object Admission {
    case object Run                          extends Admission
    final case class Wait(other: Active)     extends Admission
    final case class Answer(over: Life.Over) extends Admission
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
     * After it a `pull` ends with `None`. A finalizer that dies is not lost:
     * `cancel` dies with it, and so does a `pull` of a stream that is over,
     * next to the failure of the stream if there is one.
     *
     * A pull runs in a fiber of the bridge. That fiber has the consumer's
     * `FiberRef` values and leaves its changes of them to the consumer, also
     * when the pull fails. A fiber that the stream forks belongs to the
     * consumer's fiber. In other things it is a fiber of its own: it has its
     * own id, and `cancel` can interrupt it also where the consumer cannot be
     * interrupted.
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

    /** What a pull gave, and the fiber context it left. */
    private type Pulled = (Exit[Throwable, Option[Chunk[O]]], FiberRefs)

    def pull(): RIO[R, Option[Seq[O]]] =
      ZIO.uninterruptibleMask(restore => admit(restore))

    /**
     * Stops new pulls, interrupts the pull that is under way and closes the
     * scope when that pull has unwound. It returns when the scope is closed,
     * and it dies when a finalizer died.
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
            val closing = unwind(active).flatMap(unwound => close(Exit.unit, unwound, done))
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
          case over: Life.Over    => (Admission.Answer(over), over)
        }.flatMap {
          case Admission.Run          => run(restore, active)
          case Admission.Wait(other)  => restore(other.await.flatMap(_.await)) *> ZIO.yieldNow *> admit(restore)
          case Admission.Answer(over) => answer(restore, over)
        }
      }

    // The bridge owns the pull: it runs in a fiber of its own, so that `cancel`
    // can interrupt it without interrupting the consumer. What the stream forks
    // in it belongs to the consumer's fiber, as with a pull that the consumer
    // runs itself, and not to the fiber of this one pull.
    private def run(restore: ZIO.InterruptibilityRestorer, active: Active): RIO[R, Option[Seq[O]]] =
      ZIO.transplant { graft =>
        for {
          context <- ZIO.getFiberRefs
          fiber   <- owned(context, graft).fork
          _       <- active.succeed(fiber)
          exit    <- restore(fiber.await).onInterrupt(abandon(active, fiber))
          out     <- settle(restore, active, context, exit)
        } yield out
      }

    // The pull in its fiber. A fork changes a fiber context by the rules of its
    // refs, so the fiber first takes exactly the consumer's context. Only the
    // pull itself can be interrupted. In the error channel `None` is the end of
    // the stream and `Some` is its failure: only the end becomes a value.
    private def owned(context: FiberRefs, graft: ZIO.Grafter): URIO[R, Pulled] =
      for {
        forked <- ZIO.getFiberRefs
        _      <- ZIO.patchFiberRefs(FiberRefs.Patch.diff(forked, context))
        pulled <- graft(zPull.unsome.exit).interruptible
        after  <- ZIO.getFiberRefs
      } yield (pulled, after)

    // The consumer was interrupted while it waited. Its pull goes with it, and the
    // stream stays open.
    private def abandon(active: Active, fiber: Fiber[Any, Any]): UIO[Unit] =
      fiber.interrupt *> life.update {
        case Life.Pulling(mine) if mine eq active => Life.Idle
        case other                                => other
      }

    // The consumer goes on with what the pull made of its context, also after a
    // failure. A pull that did not come back was interrupted on the way.
    private def settle(
      restore: ZIO.InterruptibilityRestorer,
      active: Active,
      context: FiberRefs,
      exit: Exit[Nothing, Pulled]
    ): RIO[R, Option[Seq[O]]] = exit match {
      case Exit.Success((pulled, after)) =>
        ZIO.patchFiberRefs(FiberRefs.Patch.diff(context, after)) *> conclude(restore, active, pulled)
      case Exit.Failure(cause) => conclude(restore, active, Exit.failCause(cause))
    }

    private def conclude(
      restore: ZIO.InterruptibilityRestorer,
      active: Active,
      pulled: Exit[Throwable, Option[Chunk[O]]]
    ): RIO[R, Option[Seq[O]]] = pulled match {
      case Exit.Success(chunk @ Some(_)) => leave(restore, active, Life.Idle)(ZIO.succeed(chunk))
      case Exit.Success(None)            => finish(restore, active, None, Exit.unit)
      case Exit.Failure(cause)           => finish(restore, active, Some(cause), Exit.failCause(cause))
    }

    // The first end of the stream closes the scope. It answers as every later
    // pull does: with the end, or with all that went wrong.
    private def finish(
      restore: ZIO.InterruptibilityRestorer,
      active: Active,
      cause: Option[Cause[Throwable]],
      exit: Exit[Any, Any]
    ): RIO[R, Option[Seq[O]]] =
      Promise.make[Nothing, Unit].flatMap { closed =>
        val over = Life.Over(cause, closed)
        leave(restore, active, over)(close(exit, Exit.unit, closed) *> answer(restore, over))
      }

    // Gives the pull of `active` up for `next` and goes on with `mine`. When the
    // stream is over meanwhile, because it was cancelled, it answers as such a
    // stream does. Nothing else takes a pull's place away.
    private def leave(restore: ZIO.InterruptibilityRestorer, active: Active, next: Life)(
      mine: RIO[R, Option[Seq[O]]]
    ): RIO[R, Option[Seq[O]]] =
      life.modify {
        case Life.Pulling(own) if own eq active => (Option.empty[Life], next)
        case other                              => (Option(other), other)
      }.flatMap {
        case None                  => mine
        case Some(over: Life.Over) => answer(restore, over)
        case Some(other)           => ZIO.dieMessage(s"A pull of a converted ZStream lost its place to $other")
      }

    // Interrupts the pull that is under way and waits for it. A defect of its
    // unwinding is part of how the closing went.
    private def unwind(active: Option[Active]): UIO[Exit[Nothing, Unit]] =
      ZIO.foreach(active)(_.await.flatMap(_.interrupt)).map { interrupted =>
        interrupted
          .flatMap(_.causeOption)
          .flatMap(_.keepDefects)
          .fold[Exit[Nothing, Unit]](Exit.unit)(Exit.failCause(_))
      }

    // Closes the scope and passes on how that went, after what came `before` it.
    private def close(exit: Exit[Any, Any], before: Exit[Nothing, Unit], closed: Promise[Nothing, Unit]): UIO[Unit] =
      finalizer(exit).exit.flatMap(shut => closed.done(before *> shut)).unit

    // What a stream that is over answers: the end, or all that went wrong, in
    // the stream and in the closing.
    private def answer(restore: ZIO.InterruptibilityRestorer, over: Life.Over): RIO[R, Option[Seq[O]]] =
      restore(over.closed.await).exit.flatMap { closing =>
        val whole = over.cause.getOrElse(Cause.empty) ++ closing.causeOption.getOrElse(Cause.empty)
        if (whole.isEmpty) ZIO.none else ZIO.failCause(whole)
      }
  }
}
