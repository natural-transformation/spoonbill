package spoonbill.effect

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.effect.syntax.*

/**
 * One persistent reader owns upstream effects; consumers own only their waiting
 * pulls.
 */
private[effect] final class StreamRoutingRuntime[F[_], A](
  upstream: Stream[F, A],
  mode: StreamRoutingMachine.Mode,
  capacity: Int,
  fixed: Vector[StreamRoutingMachine.Lane] = Vector.empty,
  route: Option[A => Int] = None
)(using F: Effect[F]) {
  import StreamRoutingMachine.*
  require(capacity > 0, "Stream buffer capacity must be positive")
  private enum Drainer      { case Idle, Running               }
  private enum CleanupStart { case Waiting, Running, Abandoned }
  private case class Held(
    state: State[A],
    callbacks: Map[Pull, Effect.Promise[Option[A]]] = Map.empty,
    reader: Option[Effect.Promise[Boolean]] = None,
    commands: Queue[() => Unit] = Queue.empty,
    drainer: Drainer = Drainer.Idle,
    cleanupDispatching: Boolean = false
  )
  private val monitor = new Object
  private var held    = Held(fixed.foldLeft(State[A](mode, capacity))((state, lane) => add(state, lane)))

  private def report(error: Throwable): Unit =
    Reporter.PrintReporter.error("Stream operator background work failed", error)
  private def enqueue(action: () => Unit): Unit = held = held.copy(commands = held.commands.enqueue(action))
  private def publish(step: Step[A]): Boolean = {
    held = held.copy(state = step.state, cleanupDispatching = held.cleanupDispatching || step.cleanup)
    step.deliveries.foreach { delivery =>
      held.callbacks.get(delivery.pull).foreach { callback =>
        held = held.copy(callbacks = held.callbacks - delivery.pull)
        enqueue(() => callback(delivery.result))
      }
    }
    wakeReader()
    step.cleanup
  }
  private def wakeReader(): Unit = held.reader.foreach { callback =>
    if (held.state.phase != Phase.Open) {
      held = held.copy(reader = None)
      enqueue(() => callback(Right(false)))
    } else if (canRead(held.state)) {
      held = held.copy(state = beginRead(held.state), reader = None)
      enqueue(() => callback(Right(monitor.synchronized(held.state.phase == Phase.Open))))
    }
  }
  private def commit[B](body: => B): (B, Boolean) = monitor.synchronized {
    val result = body
    val run    = !held.cleanupDispatching && held.drainer == Drainer.Idle && held.commands.nonEmpty
    if (run) held = held.copy(drainer = Drainer.Running)
    (result, run)
  }
  private def change[B](body: => B): B = {
    val (result, run) = commit(body)
    if (run) drainCommands()
    result
  }
  private def drainCommands(): Unit = {
    var continue = true
    while (continue) {
      val action: Option[() => Unit] = monitor.synchronized {
        if (held.cleanupDispatching) { held = held.copy(drainer = Drainer.Idle); None }
        else
          held.commands.dequeueOption match {
            case Some((next, rest)) => held = held.copy(commands = rest); Some(next)
            case None               => held = held.copy(drainer = Drainer.Idle); None
          }
      }
      action match {
        case Some(run) =>
          try run()
          catch { case NonFatal(error) => report(error) }
        case None => continue = false
      }
    }
  }

  private def cleanup(): F[Unit] = {
    val start = new AtomicReference(CleanupStart.Waiting)
    def task = F.delay(start.compareAndSet(CleanupStart.Waiting, CleanupStart.Running)).flatMap {
      case false => F.unit
      case true  => F.delayAsync(upstream.cancel()).recover { case error => report(error); () }
    }
    val dispatch = F
      .delayAsync(F.start(task)(ExecutionContext.global))
      .flatMap { fiber =>
        F.delay(F.runAsync(fiber.join()) { case Left(error) => report(error); case _ => () })
      }
      .recoverF { case error =>
        F.delay {
          start.compareAndSet(CleanupStart.Waiting, CleanupStart.Abandoned)
          report(error)
        }.flatMap(_ => F.fail(error))
      }
    F.guarantee(dispatch)(F.delay(change {
      // Release a rejected claim and its pause atomically: an older attempt
      // must never unpause a newer retry's notifications.
      held = held.copy(
        cleanupDispatching = false,
        state = if (start.get() == CleanupStart.Abandoned) cleanupRejected(held.state) else held.state
      )
    }))
  }

  private def awaitRead(): F[Boolean] = F.promise { callback =>
    change {
      held = held.copy(reader = Some(callback))
      wakeReader()
    }
  }

  private def accept(value: A): F[Boolean] = F.delay {
    if (!monitor.synchronized(held.state.phase == Phase.Open))
      change {
        held = held.copy(state = readSettled(held.state)); false
      }
    else {
      val index = route.map(_(value))
      val target = index.map { index =>
        if (index < 0 || index >= fixed.size) throw new IllegalArgumentException(s"Invalid stream rack index: $index")
        fixed(index)
      }
      change {
        if (held.state.phase != Phase.Open) {
          held = held.copy(state = readSettled(held.state)); false
        } else if (target.exists(overflow(held.state, _)))
          publish(failed(held.state, StreamSortOverflowException(index.get, capacity)))
        else publish(received(held.state, value, target))
      }
    }
  }

  private def loop(): F[Unit] = awaitRead().flatMap {
    case false => F.unit
    case true =>
      F.delayAsync(if (monitor.synchronized(held.state.phase == Phase.Open)) upstream.pull() else F.none[A])
        .map[Either[Throwable, Option[A]]](Right(_))
        .recover { case error => Left(error) }
        .flatMap {
          case Right(Some(value)) => accept(value).recover { case error => change(publish(failed(held.state, error))) }
          case Right(None)        => F.delay(change(publish(ended(held.state))))
          case Left(error)        => F.delay(change(publish(failed(held.state, error))))
        }
        .flatMap(needsCleanup => if (needsCleanup) cleanup() else F.unit)
        .flatMap(_ => F.fork(loop())(ExecutionContext.parasitic))
  }

  private def readerExited(): F[Unit] = F.uncancelable {
    F.delay(change {
      if (held.state.phase == Phase.Open)
        publish(failed(held.state, new CancellationException("Shared upstream reader canceled")))
      else { held = held.copy(state = readSettled(held.state)); false }
    }).flatMap(needsCleanup => if (needsCleanup) cleanup() else F.unit)
  }

  private def launchReader(): Unit = {
    val reader = F.delayAsync(loop()).recoverF { case error =>
      F.delay(change(publish(failed(held.state, error))))
        .flatMap(needsCleanup => if (needsCleanup) cleanup() else F.unit)
    }
    F.runAsync(F.guarantee(reader)(readerExited())) {
      case Left(error) if monitor.synchronized(held.state.phase == Phase.Open) => report(error)
      case _                                                                   => ()
    }
  }
  launchReader()

  def newLane(): Lane = change {
    if (held.state.phase != Phase.Open) throw new IllegalStateException("Hub is closed")
    val lane = new Lane
    held = held.copy(state = add(held.state, lane))
    lane
  }

  def stream(lane: Lane): Stream[F, A] = new Stream[F, A] {
    def pull(): F[Option[A]] = StreamRoutingRuntime.protect {
      F.promise[Option[A]] { callback =>
        change {
          val ticket = new Pull
          held = held.copy(callbacks = held.callbacks.updated(ticket, callback))
          publish(StreamRoutingMachine.pull(held.state, lane, ticket))
          ()
        }
      }
    }(cancel())
    def cancel(): F[Unit] = cancelLane(Some(lane))
  }

  private def cancelLane(lane: Option[Lane]): F[Unit] = F.uncancelable {
    F.delay(change(publish(StreamRoutingMachine.cancel(held.state, lane)))).flatMap { start =>
      if (start) cleanup() else F.unit
    }
  }
  def cancel(): F[Unit] = cancelLane(None)

  /**
   * Reserve data under a parent batch lock, but never invoke source/callback
   * effects there.
   */
  def drainDeferred(lane: Lane): (Vector[A], () => Unit) = {
    val (values, run) = commit {
      val (next, values) = StreamRoutingMachine.drain(held.state, lane)
      held = held.copy(state = next)
      wakeReader()
      values
    }
    (values, () => if (run) drainCommands())
  }
}

private[effect] object StreamRoutingRuntime {

  /**
   * The protected pull's own success/error boundary, not irrevocable
   * outer-caller delivery.
   */
  def protect[F[_], A](task: => F[A])(canceled: => F[Unit])(using F: Effect[F]): F[A] =
    F.delay(new AtomicBoolean(false)).flatMap { settled =>
      F.guarantee(
        F.delayAsync(task)
          .map { value =>
            settled.set(true); value
          }
          .recoverF { case error =>
            settled.set(true)
            F.fail(error)
          }
      )(if (settled.get()) F.unit else canceled)
    }
}
