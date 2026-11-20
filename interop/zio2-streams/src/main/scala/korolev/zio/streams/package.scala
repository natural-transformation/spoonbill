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
   * The fiber of a pull. It ends with what the pull gave and the fiber context
   * that it left.
   */
  private type Puller = Fiber[Nothing, (Exit[Throwable, Any], FiberRefs)]

  /**
   * The fiber of the pull that the bridge runs. It is known once it is forked.
   */
  private type Active = Promise[Nothing, Puller]

  /**
   * What died in a pull: in the pull itself, also while it unwound, or in its
   * fiber around it.
   */
  private def died(exit: Exit[Nothing, (Exit[Throwable, Any], FiberRefs)]): Option[Cause[Nothing]] = exit match {
    case Exit.Success((pulled, _)) => pulled.causeOption.flatMap(_.keepDefects)
    case Exit.Failure(cause)       => cause.keepDefects
  }

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
     * After it a `pull` ends with `None`: `cancel` forgets the failure of the
     * stream. What died is not forgotten, in a pull or in a finalizer: every
     * `pull` of a stream that is over dies with it, next to the failure of the
     * stream if there is one. `cancel` dies with what died in the closing and
     * in the pull that was under way. The bridge can tell only what ZIO tells
     * it: a defect that is on its way out of a pull when that pull is
     * interrupted can be dropped inside the stream, as with a pull that the
     * consumer runs and interrupts itself.
     *
     * A pull runs in a fiber of the bridge. That fiber has the consumer's
     * `FiberRef` values and leaves its changes of them to the consumer, also
     * when the pull fails. A pull that was cut short leaves none: by `cancel`,
     * or because its consumer was interrupted. The closing after `cancel` has
     * the canceller's `FiberRef` values and leaves its changes to the
     * canceller. In other things the fiber of a pull is a fiber of its own: it
     * has its own id, and `cancel` can interrupt it also where the consumer
     * cannot be interrupted.
     *
     * A fiber that the stream forks belongs to the consumer's fiber, as with a
     * pull that the consumer runs itself. So `cancel` does not stop it: it
     * lives until the consumer's fiber ends. A stream whose fiber must stop
     * with the stream forks it in its scope (`forkScoped`), or interrupts it
     * when the pull is interrupted (`onInterrupt`).
     *
     * A consumer that is interrupted while it waits takes its pull with it.
     * What died while the pull unwound is in the exit of the consumer. The
     * stream stays open, unless that pull had just met its end.
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
     * scope when that pull has unwound. It returns when the scope is closed. It
     * dies with what died in the closing and in the pull that was under way.
     * What an earlier pull died with is not told again here: the pulls tell it.
     */
    def cancel(): RIO[R, Unit] =
      ZIO.uninterruptibleMask { restore =>
        Promise.make[Nothing, Unit].flatMap { closed =>
          life.modify {
            case Life.Idle            => ((true, Option.empty[Active], closed), Life.Over(None, closed))
            case Life.Pulling(active) => ((true, Option(active), closed), Life.Over(None, closed))
            // A stream that failed forgets its failure here: after `cancel` a pull gets the end.
            // What a pull died with is not forgotten: a pull still dies with it.
            case over: Life.Over =>
              ((false, Option.empty[Active], over.closed), Life.Over(over.cause.flatMap(_.keepDefects), over.closed))
          }.flatMap { case (first, active, done) =>
            ZIO.when(first)(shut(restore, active, done)) *> restore(done.await)
          }
        }
      }

    // Only an idle stream admits a pull. A second pull waits for the first, and a
    // stream that is over answers at once.
    private def admit(restore: ZIO.InterruptibilityRestorer): RIO[R, Option[Seq[O]]] =
      Promise.make[Nothing, Puller].flatMap { active =>
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

    // The pull in its fiber, with exactly the consumer's fiber context. Only the
    // pull itself can be interrupted. What it comes back with is taken where no
    // interruption can drop it: ZIO drops a defect that is on its way through a
    // handler when the fiber is interrupted there. So a pull that was
    // interrupted comes back too, with the interruption as its cause. In the
    // error channel `None` is the end of the stream and `Some` is its failure:
    // only the end becomes a value.
    private def owned(context: FiberRefs, graft: ZIO.Grafter): URIO[R, Pulled] =
      within(context)(graft(zPull.interruptible).unsome.exit)

    // Runs `work` in a fiber of the bridge as the fiber with `context` would. A
    // fork changes a fiber context by the rules of its refs, so the fiber first
    // takes exactly that context. It tells what the work left of it.
    private def within[A](context: FiberRefs)(work: URIO[R, A]): URIO[R, (A, FiberRefs)] =
      for {
        forked <- ZIO.getFiberRefs
        _      <- ZIO.patchFiberRefs(FiberRefs.Patch.diff(forked, context))
        result <- work
        after  <- ZIO.getFiberRefs
      } yield (result, after)

    // The consumer was interrupted while it waited. Its pull goes with it, and the
    // stream stays open. A pull that has come back all the same is settled as
    // any pull: when it met the end of the stream, the scope is closed. A chunk
    // that it brought is lost with the consumer, as with a pull that the consumer
    // runs itself. What died goes into the exit of the consumer. The consumer
    // takes nothing of the fiber context of that pull: it holds the interruption
    // of the pull, and the consumer has its own.
    private def abandon(active: Active, fiber: Fiber[Nothing, Pulled]): URIO[R, Unit] =
      fiber.interrupt.flatMap {
        case Exit.Success((Exit.Failure(cause), _)) if cause.isInterrupted =>
          leave(waiting, active, Life.Idle)(ZIO.failCause(cause))
        case Exit.Success((pulled, _)) => conclude(waiting, active, pulled, ZIO.unit)
        case Exit.Failure(cause)       => conclude(waiting, active, Exit.failCause(cause), ZIO.unit)
      }.exit.flatMap(gone => ZIO.foreachDiscard(gone.causeOption.flatMap(_.keepDefects))(ZIO.failCause(_)))

    // An interrupted consumer waits for a closing where it stands: it cannot be
    // interrupted again.
    private def waiting: ZIO.InterruptibilityRestorer = ZIO.InterruptibilityRestorer.MakeUninterruptible

    // The consumer goes on with what the pull made of its context, also after a
    // failure. It takes that context when it takes the result of the pull, and
    // not from a pull that `cancel` took the stream from: such a pull may hold
    // its own interruption in its context, and the consumer was not interrupted.
    // The fiber of a pull fails only when something outside the pull went wrong
    // in it.
    private def settle(
      restore: ZIO.InterruptibilityRestorer,
      active: Active,
      context: FiberRefs,
      exit: Exit[Nothing, Pulled]
    ): RIO[R, Option[Seq[O]]] = exit match {
      case Exit.Success((pulled, after)) =>
        conclude(restore, active, pulled, ZIO.patchFiberRefs(FiberRefs.Patch.diff(context, after)))
      case Exit.Failure(cause) => conclude(restore, active, Exit.failCause(cause), ZIO.unit)
    }

    // `adopt` runs only for a pull that still has its place in the stream.
    private def conclude(
      restore: ZIO.InterruptibilityRestorer,
      active: Active,
      pulled: Exit[Throwable, Option[Chunk[O]]],
      adopt: UIO[Unit]
    ): RIO[R, Option[Seq[O]]] = pulled match {
      case Exit.Success(chunk @ Some(_)) => leave(restore, active, Life.Idle)(adopt *> ZIO.succeed(chunk))
      case Exit.Success(None)            => finish(restore, active, None, Exit.unit, adopt)
      case Exit.Failure(cause)           => finish(restore, active, Some(cause), Exit.failCause(cause), adopt)
    }

    // The first end of the stream closes the scope. It answers as every later
    // pull does: with the end, or with all that went wrong.
    private def finish(
      restore: ZIO.InterruptibilityRestorer,
      active: Active,
      cause: Option[Cause[Throwable]],
      exit: Exit[Any, Any],
      adopt: UIO[Unit]
    ): RIO[R, Option[Seq[O]]] =
      Promise.make[Nothing, Unit].flatMap { closed =>
        val over = Life.Over(cause, closed)
        leave(restore, active, over)(adopt *> close(exit, Exit.unit, closed) *> answer(restore, over))
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

    // The closing after `cancel` runs by itself. A canceller that is interrupted
    // leaves nothing half done. The closing has the canceller's fiber context,
    // and a canceller that waits for it takes what the closing left of it.
    private def shut(
      restore: ZIO.InterruptibilityRestorer,
      active: Option[Active],
      closed: Promise[Nothing, Unit]
    ): URIO[R, Unit] =
      for {
        context <- ZIO.getFiberRefs
        closing  = unwind(active).flatMap(unwound => close(Exit.unit, unwound, closed))
        fiber   <- within(context)(closing).forkDaemon
        exit    <- restore(fiber.await)
        _       <- exit.foldExit(_ => ZIO.unit, left => ZIO.patchFiberRefs(FiberRefs.Patch.diff(context, left._2)))
      } yield ()

    // Interrupts the pull that is under way and waits for it. What died in it is
    // part of how the closing went: a defect of its unwinding, and a defect that
    // the pull had come back with when `cancel` took the stream.
    private def unwind(active: Option[Active]): UIO[Exit[Nothing, Unit]] =
      ZIO.foreach(active)(_.await.flatMap(_.interrupt)).map { interrupted =>
        interrupted.flatMap(died).fold[Exit[Nothing, Unit]](Exit.unit)(Exit.failCause(_))
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
