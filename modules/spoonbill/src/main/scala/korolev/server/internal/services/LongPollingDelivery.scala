package spoonbill.server.internal.services

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import spoonbill.effect.{AsyncTable, Effect, Queue, Reporter, Scheduler, StateCell, Stream}
import spoonbill.effect.syntax.*
import spoonbill.internal.Frontend

/**
 * The delivery of one long-polling entry: the interpreter of
 * [[PollDeliveryMachine]].
 *
 * The machine decides. This class keeps the handles: the frame that was
 * handed out last, the completion handle of each poll that waits, the timer of
 * each such poll, and the frames of the view once the entry owns one. A frame
 * is pulled from the view only when the machine asks for it, so the view still
 * drops a browser command that expires while it waits in its queue.
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

  /** The machine state, with the handles that it names. They change in one step. */
  private final case class Held(
    state: PollDeliveryMachine.State[Long],
    frame: Option[Frontend.OutgoingFrame],
    waiters: Map[Long, Answer]
  )

  private val held   = new StateCell(Held(PollDeliveryMachine.initial[Long], None, Map.empty))
  private val ids    = new AtomicLong()
  private val timers = TrieMap.empty[Long, Scheduler.JobHandler[F, Unit]]

  // The frames of the view, once this entry owns one. A pull waits for them,
  // so a poll that joins a first attach waits for that attach.
  private val source = AsyncTable.unsafeCreateEmpty[F, Unit, Stream[F, Frontend.OutgoingFrame]]

  // One element for each poll that is progress: it confirmed a frame, or the
  // page has everything. The idle timer of the view watches this stream.
  private val progressQueue      = Queue[F, Unit]()
  val progress: Stream[F, Unit] = progressQueue.stream

  /** The entry owns the view. Its frames come from `frames` from now on. */
  def own(frames: Stream[F, Frontend.OutgoingFrame]): F[Unit] = source.put((), frames)

  /**
   * The entry will own no view: every poll that waits, and every later one, is
   * told to reload. A pull that waits for the frames ends too.
   */
  def abandon(): F[Unit] =
    source.fail((), new IllegalStateException("The long-polling entry owns no view")).flatMap(_ => Effect[F].delay(ended()))

  /** What becomes of a message with cursor `seen`. */
  def message(seen: Long): PollDeliveryMachine.Message = PollDeliveryMachine.message(held.get.state, seen)

  /**
   * One poll with cursor `seen`. A poll whose request is cancelled is taken out
   * of the waiters; the pull that it may have started goes on for the entry.
   */
  def poll(seen: Long): F[PollAnswer] = {
    val id = ids.incrementAndGet()
    Effect[F].guarantee(Effect[F].promise[PollAnswer] { answer =>
      val (result, kept) = held.modify { current =>
        // Only a command can expire. The view says whether it still waits for its answer.
        val awaited        = current.frame.forall(_.awaited())
        val (next, result) = PollDeliveryMachine.step(current.state, Event.Poll(id, seen, awaited))
        val waiters = result match {
          case Result.Wait(_) => current.waiters.updated(id, answer)
          case _              => current.waiters
        }
        (Held(next, current.frame, waiters), (result, current.frame))
      }
      result match {
        case Result.Reload() => answer(Right(PollAnswer.Reload))
        case Result.Stale()  => answer(Right(PollAnswer.Stale))
        case Result.Answer(frame, dom, keepAlive) =>
          val message = if (keepAlive) Frontend.KeepAliveMessage else kept.fold(Frontend.KeepAliveMessage)(_.message)
          answer(Right(PollAnswer.Frame(message, frame, dom)))
        case Result.Wait(pull) =>
          progressQueue.offerUnsafe(())
          if (pull) startPull()
          startTimer(id)
        case _ => ()
      }
    })(Effect[F].delay(settle(Event.Cancelled(id))))
  }

  /** Asks the view for one frame. The pull belongs to the entry, not to a request. */
  private def startPull(): Unit =
    Effect[F].runAsync(source.get(()).flatMap(_.pull())) {
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
          (Held(next, Some(frame), current.waiters -- waiting), Some((waiting, number, dom, current.waiters)))
        case _ => (current, None)
      }
    }
    answered.foreach { case (waiting, number, dom, waiters) =>
      waiting.foreach { id =>
        stopTimer(id)
        waiters.get(id).foreach(_(Right(PollAnswer.Frame(frame.message, number, dom))))
      }
    }
  }

  private def ended(): Unit = {
    val reloaded = held.modify { current =>
      PollDeliveryMachine.step(current.state, Event.Ended()) match {
        case (next, Result.Reloaded(waiting)) =>
          (Held(next, None, current.waiters -- waiting), waiting.flatMap(id => current.waiters.get(id).map(id -> _)))
        case (next, _) => (Held(next, None, current.waiters), Vector.empty)
      }
    }
    reloaded.foreach { case (id, answer) =>
      stopTimer(id)
      answer(Right(PollAnswer.Reload))
    }
    progressQueue.unsafeClose()
  }

  /** A timeout or a cancellation. Only the first event that the machine sees settles a poll. */
  private def settle(event: Event[Long]): Unit = {
    val id = event match {
      case Event.TimedOut(value)  => value
      case Event.Cancelled(value) => value
      case _                      => -1L
    }
    val answer = held.modify { current =>
      val (next, result) = PollDeliveryMachine.step(current.state, event)
      val waiters        = if (next.waiting.contains(id)) current.waiters else current.waiters - id
      val settled = result match {
        case Result.NoContent() => current.waiters.get(id)
        case _                  => None
      }
      (Held(next, current.frame, waiters), settled)
    }
    stopTimer(id)
    answer.foreach(_(Right(PollAnswer.NoContent)))
  }

  private def startTimer(id: Long): Unit =
    Effect[F].runAsync(timing.scheduleOnce(pollWait)(Effect[F].delay(settle(Event.TimedOut(id))))) {
      case Right(handle) =>
        // A poll that was settled meanwhile leaves the handle; it ends at its time.
        if (held.get.state.waiting.contains(id)) timers.put(id, handle)
        else handle.unsafeCancel()
      case Left(error) => reporter.error("Unable to schedule the wait limit of a long poll", error)
    }

  private def stopTimer(id: Long): Unit = timers.remove(id).foreach(_.unsafeCancel())

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
