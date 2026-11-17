package spoonbill.effect

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import spoonbill.effect.syntax.*

private[effect] final class StreamBufferRuntime[F[_], A](
  upstream: Stream[F, A],
  duration: FiniteDuration,
  capacity: Int,
  schedule: (() => F[Unit]) => Scheduler.JobHandler[F, Unit]
)(using F: Effect[F], ec: ExecutionContext, reporter: Reporter)
    extends Stream[F, Seq[A]] {
  import StreamBatchMachine.*
  private case class Entry(
    callback: Effect.Promise[Option[Seq[A]]],
    timer: Option[Scheduler.JobHandler[F, Unit]] = None
  )
  private case class Held(state: State[A] = State[A](), entries: Map[Ticket, Entry] = Map.empty)
  private val monitor = new Object
  private var held    = Held()
  private val lane    = new StreamRoutingMachine.Lane
  private val routing = StreamRoutingRuntime(upstream, StreamRoutingMachine.Mode.Buffered, capacity, Vector(lane))
  private val input   = routing.stream(lane)

  private def notify(callback: Effect.Promise[Option[Seq[A]]], result: Either[Throwable, Option[Seq[A]]]): Unit =
    try callback(result)
    catch { case NonFatal(error) => reporter.error("Buffered stream callback failed", error) }

  private def detach(ticket: Ticket): (Entry, Option[Ticket]) = {
    val entry         = held.entries(ticket)
    val (state, next) = StreamBatchMachine.finish(held.state, ticket)
    held = Held(state, held.entries - ticket)
    (entry, next)
  }

  private def finish(ticket: Ticket, result: Either[Throwable, Option[Seq[A]]]): Unit = {
    val owned = monitor.synchronized {
      if (!owns(held.state, ticket)) None
      else {
        Some(detach(ticket))
      }
    }
    owned.foreach { case (entry, next) =>
      notify(entry.callback, result)
      next.foreach(start)
    }
  }

  private def elapsed(ticket: Ticket): Unit = {
    val completed = monitor.synchronized {
      if (!owns(held.state, ticket)) None
      else
        held.state.active.flatMap(_.first).map { first =>
          // Reserve the complete batch before granting its successor. Routing
          // queues effects for later; no source or callback runs under this lock.
          val (rest, resume) = routing.drainDeferred(lane)
          val (entry, next)  = detach(ticket)
          (entry, next, first +: rest, resume)
        }
    }
    completed.foreach { case (entry, next, values, resume) =>
      // Match eager Queue.dequeueAll credit publication: a continuation may
      // wait for the next upstream read to enter. The selected batch is fixed.
      resume()
      try notify(entry.callback, Right(Some(values)))
      finally next.foreach(start)
    }
  }

  private def received(ticket: Ticket, result: Either[Throwable, Option[A]]): Unit = result match {
    case Left(error) => finish(ticket, Left(error))
    case Right(None) => finish(ticket, Right(None))
    case Right(Some(value)) =>
      val active = monitor.synchronized {
        val active = owns(held.state, ticket)
        if (active) held = held.copy(state = first(held.state, ticket, value))
        active
      }
      if (active) {
        try {
          val timer = schedule(() => F.delay(elapsed(ticket)))
          val installed = monitor.synchronized {
            if (owns(held.state, ticket)) {
              held = held.copy(entries = held.entries.updated(ticket, held.entries(ticket).copy(timer = Some(timer))))
              true
            } else false
          }
          if (!installed) timer.unsafeCancel()
        } catch { case NonFatal(error) => finish(ticket, Left(error)) }
      }
  }

  private def start(ticket: Ticket): Unit =
    if (monitor.synchronized(owns(held.state, ticket))) {
      try F.runAsync(F.fork(input.pull())(ExecutionContext.parasitic))(received(ticket, _))
      catch { case NonFatal(error) => finish(ticket, Left(error)) }
    }

  def pull(): F[Option[Seq[A]]] = StreamRoutingRuntime.protect {
    if (duration.toMillis <= 0) input.pull().map(_.map(value => Seq(value)))
    else
      F.promise[Option[Seq[A]]] { callback =>
        val ticket = new Ticket
        val admission = monitor.synchronized {
          if (held.state.phase == Phase.Closed) None
          else {
            val (state, startNow) = begin(held.state, ticket)
            held = Held(state, held.entries.updated(ticket, Entry(callback)))
            Some(startNow)
          }
        }
        admission match {
          case None       => callback(Right(None))
          case Some(true) => start(ticket)
          case _          => ()
        }
      }
  }(cancel())

  def cancel(): F[Unit] = F.uncancelable {
    F.delay {
      val entries = monitor.synchronized {
        val entries = held.entries.values.toVector
        held = Held(close(held.state), Map.empty)
        entries
      }
      entries.foreach(_.timer.foreach(_.unsafeCancel()))
      entries
    }.flatMap { entries =>
      // Cleanup dispatch must precede notifications: Future continuations can
      // synchronously wait for the source cancellation to have started.
      F.guarantee(routing.cancel())(F.delay(entries.foreach(entry => notify(entry.callback, Right(None)))))
    }
  }
}
