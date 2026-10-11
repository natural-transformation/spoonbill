package spoonbill.auth

import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.effect.Effect

/**
 * Bounded serial ownership across provider settlement and local publication.
 * Only turn-grant callbacks are queued. Each operation runs in its original
 * caller effect, preserving native fiber-local state and runtime context.
 * Cancellation cannot abandon an admitted operation or its cleanup.
 */
private[auth] final class BrowserCoordinatorGate[F[_]](capacity: Int)(using F: Effect[F]) {
  require(capacity > 0)
  private enum Admission { case Ordinary, Cleanup, Close }
  private val lock         = new Object
  private var pending      = Queue.empty[Effect.Promise[Boolean]]
  private var running      = false
  private var closing      = false
  private var closeResult  = Option.empty[Either[Throwable, Unit]]
  private var closeWaiters = List.empty[Effect.Promise[Boolean]]

  private def reject: Throwable = new IllegalStateException("Browser coordinator is closed or at capacity")
  private def signal(callback: Effect.Promise[Boolean], result: Either[Throwable, Boolean]): Unit =
    ExecutionContext.parasitic.execute(() => callback(result))

  /** true owns a turn; false joins an already settled global close. */
  private def acquire(kind: Admission): F[Boolean] = F.promise { callback =>
    val outcome = lock.synchronized {
      if (kind == Admission.Ordinary && (closing || (running && pending.size >= capacity))) Some(Left(reject))
      else if (kind != Admission.Ordinary && closing) {
        closeResult match {
          case Some(result) => Some(result.map(_ => false))
          case None         => closeWaiters = callback :: closeWaiters; None
        }
      } else {
        if (kind == Admission.Close) closing = true
        if (running) { pending = pending.enqueue(callback); None }
        else { running = true; Some(Right(true)) }
      }
    }
    outcome.foreach(signal(callback, _))
  }

  private def finished(closeOwner: Boolean, result: Either[Throwable, Unit]): Unit = {
    val (waiters, next) = lock.synchronized {
      val waiters = if (closeOwner) {
        closeResult = Some(result)
        val all = closeWaiters
        closeWaiters = Nil
        all
      } else Nil
      val next = pending.dequeueOption match {
        case Some((callback, rest)) => pending = rest; Some(callback)
        case None                   => running = false; None
      }
      waiters -> next
    }
    waiters.foreach(callback =>
      try signal(callback, result.map(_ => false))
      catch { case NonFatal(_) => () }
    )
    next.foreach(signal(_, Right(true)))
  }

  private def execute[A](operation: => F[A], closeOwner: Boolean): F[A] = {
    val attempted = F.recover(F.map(F.delayAsync(operation))(value => Right(value): Either[Throwable, A])) {
      case error => Left(error)
    }
    F.flatMap(attempted) { result =>
      F.flatMap(F.delay(finished(closeOwner, result.map(_ => ())))) { _ =>
        result.fold(F.fail, F.pure)
      }
    }
  }

  def apply[A](operation: => F[A]): F[A] = F.uncancelable {
    F.flatMap(acquire(Admission.Ordinary))(_ => execute(operation, closeOwner = false))
  }

  /**
   * One reserved release per capacity-bounded live owner. During shutdown the
   * final close owns releases, so cleanup joins that operation's actual result.
   */
  def cleanup(operation: => F[Unit]): F[Unit] = F.uncancelable {
    F.flatMap(acquire(Admission.Cleanup)) {
      case true  => execute(operation, closeOwner = false)
      case false => F.unit
    }
  }
  def close(operation: => F[Unit]): F[Unit] = F.uncancelable {
    F.flatMap(acquire(Admission.Close)) {
      case true  => execute(operation, closeOwner = true)
      case false => F.unit
    }
  }
}
