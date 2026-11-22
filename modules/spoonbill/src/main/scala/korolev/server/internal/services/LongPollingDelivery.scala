package spoonbill.server.internal.services

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler, StateCell, Stream}
import spoonbill.effect.syntax.*
import spoonbill.internal.Frontend

/**
 * The delivery of one long-polling entry: the interpreter of
 * [[PollDeliveryMachine]].
 *
 * The machine decides. This class keeps the handles: the frame that was
 * handed out last, the completion handle of each poll that waits, the timer of
 * each such poll, and the frames of the view once the entry owns one. They live
 * in one cell with the machine state and change in the same step, so no handle
 * outlives the poll that the machine settled. A frame is pulled from the view
 * only when the machine asks for it, so the view still drops a browser command
 * that expires while it waits in its queue.
 *
 * @param pollWait
 *   how long a poll waits for a frame at most. It is then answered with `204`.
 */
private[services] final class LongPollingDelivery[F[_]: Effect](
  pollWait: FiniteDuration,
  timing: LongPollingDelivery.Timing[F],
  reporter: Reporter
) {
  import LongPollingDelivery.*
  import PollDeliveryMachine.{Event, Kind, Result}

  private type Answer = Either[Throwable, PollAnswer] => Unit
  private type Timer  = Scheduler.JobHandler[F, Unit]
  private type Frames = Stream[F, Frontend.OutgoingFrame]

  /** A poll that waits: its completion handle, and its timer once the scheduler has set it. */
  private final case class Waiter(answer: Answer, timer: Option[Timer])

  /** The machine state, with the handles that it names. They change in one step. */
  private final case class Held(
    state: PollDeliveryMachine.State[Long],
    frame: Option[Frontend.OutgoingFrame],
    waiters: Map[Long, Waiter],
    frames: Option[Frames]
  )

  private val held = new StateCell(Held(PollDeliveryMachine.initial[Long], None, Map.empty, None))
  private val ids  = new AtomicLong()

  // One element for each poll that is progress: it confirmed a frame, or the
  // page has everything. The idle timer of the view watches this stream.
  private val progressQueue      = Queue[F, Unit]()
  val progress: Stream[F, Unit] = progressQueue.stream

  /**
   * The entry owns the view. Its frames come from `frames` from now on, and a
   * poll that joined the first attach gets its frame now.
   */
  def own(frames: Frames): F[Unit] = Effect[F].delay {
    val pull = held.modify { current =>
      val (next, result) = PollDeliveryMachine.step(current.state, Event.Owned())
      val owns           = next.owned && !current.state.owned
      (current.copy(state = next, frames = if (owns) Some(frames) else current.frames), result == Result.Pull[Long]())
    }
    if (pull) startPull(frames)
  }

  /** The entry will own no view: every poll that waits, and every later one, is told to reload. */
  def abandon(): F[Unit] = Effect[F].delay(ended())

  /** What becomes of a message with cursor `seen`. */
  def message(seen: Long): PollDeliveryMachine.Message = PollDeliveryMachine.message(held.get.state, seen)

  /**
   * One poll with cursor `seen`. When its request ends, however it ends, the
   * machine hears of it: a poll that still waits, as after a cancellation, is
   * taken out with its timer; for an answered poll nothing changes. The pull
   * that a poll may have started goes on for the entry.
   */
  def poll(seen: Long): F[PollAnswer] = {
    val id = ids.incrementAndGet()
    Effect[F].guarantee(Effect[F].promise[PollAnswer] { answer =>
      val (result, kept, frames) = held.modify { current =>
        // Only a command can expire. The view says whether it still waits for its answer.
        val awaited        = current.frame.forall(_.awaited())
        val (next, result) = PollDeliveryMachine.step(current.state, Event.Poll(id, seen, awaited))
        val waiters = result match {
          case Result.Wait(_) => current.waiters.updated(id, Waiter(answer, None))
          case _              => current.waiters
        }
        // A frame that the page confirmed is not kept any more.
        val frame = if (next.kept.isEmpty) None else current.frame
        (Held(next, frame, waiters, current.frames), (result, frame, current.frames))
      }
      result match {
        case Result.Reload() => answer(Right(PollAnswer.Reload))
        case Result.Stale()  => answer(Right(PollAnswer.Stale))
        case Result.Answer(frame, dom, keepAlive) =>
          val message = if (keepAlive) Frontend.KeepAliveMessage else kept.fold(Frontend.KeepAliveMessage)(_.message)
          answer(Right(PollAnswer.Frame(message, frame, dom)))
        case Result.Wait(pull) =>
          progressQueue.offerUnsafe(())
          // The machine asks for a frame only when the entry owns its view.
          if (pull) frames.foreach(startPull)
          startTimer(id)
        case _ => ()
      }
    })(Effect[F].delay(settle(id, Event.RequestEnded(id))))
  }

  /** Asks the view for one frame. The pull belongs to the entry, not to a request. */
  private def startPull(frames: Frames): Unit =
    Effect[F].runAsync(frames.pull()) {
      case Right(Some(frame)) => pulled(frame)
      case Right(None)        => ended()
      case Left(error) =>
        reporter.debug(s"Long-polling view ended: ${error.getClass.getSimpleName}")
        ended()
    }

  private def pulled(frame: Frontend.OutgoingFrame): Unit = {
    val answered = held.modify { current =>
      PollDeliveryMachine.step(current.state, Event.Pulled(kind(frame.kind))) match {
        case (next, Result.Answered(waiting, number, dom)) =>
          val waiters = waiting.flatMap(current.waiters.get)
          (Held(next, Some(frame), current.waiters -- waiting, current.frames), waiters.map((_, number, dom)))
        case _ => (current, Vector.empty)
      }
    }
    answered.foreach { case (waiter, number, dom) =>
      waiter.timer.foreach(_.unsafeCancel())
      waiter.answer(Right(PollAnswer.Frame(frame.message, number, dom)))
    }
  }

  private def ended(): Unit = {
    val reloaded = held.modify { current =>
      val (next, result) = PollDeliveryMachine.step(current.state, Event.Ended())
      val waiting = result match {
        case Result.Reloaded(ids) => ids
        case _                    => Vector.empty
      }
      (Held(next, None, current.waiters -- waiting, current.frames), waiting.flatMap(current.waiters.get))
    }
    reloaded.foreach { waiter =>
      waiter.timer.foreach(_.unsafeCancel())
      waiter.answer(Right(PollAnswer.Reload))
    }
    progressQueue.unsafeClose()
  }

  /** A timeout or the end of a request. Only the first event that the machine sees settles a poll. */
  private def settle(id: Long, event: Event[Long]): Unit = {
    val (settled, noContent) = held.modify { current =>
      val (next, result) = PollDeliveryMachine.step(current.state, event)
      // The handles of a poll that no longer waits leave with it.
      val settled = if (next.waiting.contains(id)) None else current.waiters.get(id)
      val waiters = if (settled.isEmpty) current.waiters else current.waiters - id
      (Held(next, current.frame, waiters, current.frames), (settled, result == Result.NoContent[Long]()))
    }
    settled.foreach { waiter =>
      waiter.timer.foreach(_.unsafeCancel())
      if (noContent) waiter.answer(Right(PollAnswer.NoContent))
    }
  }

  private def startTimer(id: Long): Unit =
    Effect[F].runAsync(timing.scheduleOnce(pollWait)(Effect[F].delay(settle(id, Event.TimedOut(id))))) {
      case Right(timer) =>
        // The timer joins its poll in one step, or it is cancelled: the poll
        // was settled before the scheduler gave the timer back.
        val keeps = held.modify { current =>
          current.waiters.get(id) match {
            case Some(waiter) if PollDeliveryMachine.keepsTimer(current.state, id) =>
              (current.copy(waiters = current.waiters.updated(id, waiter.copy(timer = Some(timer)))), true)
            case _ => (current, false)
          }
        }
        if (!keeps) timer.unsafeCancel()
      case Left(error) => reporter.error("Unable to schedule the wait limit of a long poll", error)
    }

  private def kind(value: Frontend.FrameKind): Kind = value match {
    case Frontend.FrameKind.Dom     => Kind.Dom
    case Frontend.FrameKind.Command => Kind.Command
    case Frontend.FrameKind.Other   => Kind.Other
  }
}

private[services] object LongPollingDelivery {

  /** What a poll is answered with. */
  enum PollAnswer {

    /** Frame `number` of the view, and the DOM revision after it. */
    case Frame(message: String, number: Long, dom: Long)

    /** The reload frame. */
    case Reload

    /** `410`: the poll is older than what the page has confirmed. */
    case Stale

    /** `204`: nothing yet. */
    case NoContent
  }

  /** Internal timing seam for the wait limit of a poll. */
  trait Timing[F[_]] {
    def scheduleOnce(delay: FiniteDuration)(job: => F[Unit]): F[Scheduler.JobHandler[F, Unit]]
  }
}
