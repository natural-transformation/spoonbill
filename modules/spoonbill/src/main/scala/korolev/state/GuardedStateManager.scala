package spoonbill.state

import avocet.Id
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.tailrec
import scala.util.control.NonFatal
import spoonbill.effect.{Effect, Queue}
import spoonbill.effect.syntax.*
import spoonbill.server.SessionAccessDenied

/** Guards every state-manager entry point without recursively reading through
  * this wrapper. Closing stops new mutations and drains admitted writes before
  * a local replacement can restore the same underlying manager. A distributed
  * repository still requires a receiver-side ownership fence in its transaction.
  */
private[spoonbill] final class GuardedStateManager[F[_]: Effect, S: StateDeserializer](
  underlying: StateManager[F],
  authorize: S => F[Unit],
  persist: Option[S => F[Unit]] = None,
  onPersistenceFailure: Option[Throwable => F[Unit]] = None
) extends StateManager[F] {
  private case class Writes(closing: Boolean, count: Int, waiters: List[Effect.Promise[Unit]])
  private val writes = new AtomicReference(Writes(false, 0, Nil))
  private val persistentWrites = persist.map { _ =>
    val queue = Queue[F, () => F[Unit]](128)
    Effect[F].runAsync(queue.stream.foreach(job => job()))(_ => ())
    queue
  }

  private def inWriteOrder(operation: => F[Unit]): F[Unit] = persistentWrites match {
    case None => Effect[F].delayAsync(operation)
    case Some(queue) => Effect[F].promise[Unit] { callback =>
      val job = () => Effect[F].delayAsync(operation).recoverF { case error =>
        onPersistenceFailure.fold(Effect[F].fail[Unit](error))(fail => fail(error))
      }.map(_ => callback(Right(())))
        .recover { case error => callback(Left(error)) }
      if (!queue.offerUnsafe(job)) callback(Left(new SessionAccessDenied))
    }
  }

  private def current: F[S] = underlying.read[S](Id.TopLevel).flatMap {
    case Some(value) => Effect[F].pure(value)
    case None => Effect[F].fail(new SessionAccessDenied)
  }
  private def check: F[Unit] = current.flatMap(authorize)

  def snapshot: F[StateManager.Snapshot] = check.flatMap(_ => underlying.snapshot).flatMap { snapshot =>
    snapshot[S](Id.TopLevel) match {
      case Some(state) => authorize(state).as(new StateManager.Snapshot {
        // Legacy in-memory snapshots may be live views; pin the authorized root
        // so rendering and its producer guard refer to the same presentation.
        def apply[T: StateDeserializer](nodeId: Id): Option[T] =
          if (nodeId == Id.TopLevel) Some(state.asInstanceOf[T]) else snapshot[T](nodeId)
      })
      case None => Effect[F].fail(new SessionAccessDenied)
    }
  }

  def read[T: StateDeserializer](nodeId: Id): F[Option[T]] = check.flatMap(_ => underlying.read[T](nodeId))

  def write[T: StateSerializer](nodeId: Id, value: T): F[Unit] = mutation(inWriteOrder {
    check.flatMap { _ =>
      // Top-level identity is the runtime's existing StateManager type boundary.
      val candidate = if (nodeId == Id.TopLevel) authorize(value.asInstanceOf[S]) else Effect[F].unit
      candidate.flatMap { _ =>
        if (nodeId == Id.TopLevel) persist.fold(Effect[F].unit)(save => save(value.asInstanceOf[S]))
        else Effect[F].unit
      }.flatMap(_ => Effect[F].delayAsync(underlying.write(nodeId, value)))
    }
  })

  def delete(nodeId: Id): F[Unit] = mutation(inWriteOrder(check.flatMap { _ =>
    if (nodeId == Id.TopLevel && persist.nonEmpty) Effect[F].fail(new SessionAccessDenied)
    else underlying.delete(nodeId)
  }))

  private def mutation(operation: => F[Unit]): F[Unit] = {
    @tailrec def begin(): Unit = {
      val before = writes.get()
      if (before.closing) throw new SessionAccessDenied
      if (!writes.compareAndSet(before, before.copy(count = before.count + 1))) begin()
    }
    @tailrec def finish(): Unit = {
      val before = writes.get()
      val drain = before.closing && before.count == 1
      val after = before.copy(count = before.count - 1, waiters = if (drain) Nil else before.waiters)
      if (!writes.compareAndSet(before, after)) finish()
      else if (drain) before.waiters.foreach(_(Right(())))
    }
    // Register and start together, without a cancellation boundary between
    // incrementing the admitted-write count and starting its completion observer.
    Effect[F].promise[Unit] { callback =>
      begin()
      val completed = new AtomicBoolean(false)
      def end(result: Either[Throwable, Unit]): Unit =
        if (completed.compareAndSet(false, true)) {
          finish()
          callback(result)
        }
      try Effect[F].runAsync(Effect[F].delayAsync(operation))(end)
      catch { case NonFatal(error) => end(Left(error)) }
    }
  }

  def closeAndDrain(): F[Unit] = Effect[F].promise[Unit] { callback =>
    @tailrec def close(): Unit = {
      val before = writes.get()
      val after = before.copy(closing = true, waiters = if (before.count == 0) Nil else callback :: before.waiters)
      if (!writes.compareAndSet(before, after)) close()
      else if (before.count == 0) callback(Right(()))
    }
    close()
  }.flatMap(_ => persistentWrites.fold(Effect[F].unit)(_.close()))
}
