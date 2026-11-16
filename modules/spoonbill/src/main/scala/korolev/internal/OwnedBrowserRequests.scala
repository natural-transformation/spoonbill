package spoonbill.internal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import spoonbill.effect.Effect

/** Connection-owned pending requests. Register precedes emission; reply, expiry,
  * send failure and close compete to remove the same entry. Unknown replies can
  * never create entries. Callbacks and deadline cancellation run outside CAS.
  */
private[spoonbill] final class OwnedBrowserRequests[F[_]: Effect, A](
  deadlines: Frontend.RpcDeadlineScheduler,
  maxPending: Int,
  closedError: () => Throwable
) {
  require(maxPending > 0, "Pending browser request limit must be positive")

  private final class Entry(initialCallback: Effect.Promise[A]) {
    val token = new PendingRegistryMachine.Token
    private val finished = new AtomicBoolean(false)
    private val callback = new AtomicReference(Option(initialCallback))
    private val cancellation = new AtomicReference(Option.empty[() => Unit])

    def install(cancel: () => Unit): Unit = {
      cancellation.set(Some(cancel))
      if (finished.get()) cancellation.getAndSet(None).foreach(_.apply())
    }

    def finish(result: Either[Throwable, A]): Unit = {
      if (finished.compareAndSet(false, true)) {
        val notify = callback.getAndSet(None)
        cancellation.getAndSet(None).foreach { cancel =>
          try cancel() catch { case NonFatal(_) => () }
        }
        notify.foreach(_(result))
      }
    }
  }

  private case class State(registry: PendingRegistryMachine.State[String], entries: Map[String, Entry])
  private val state = new AtomicReference(State(PendingRegistryMachine.initial[String], Map.empty))

  @tailrec private def register(key: String, entry: Entry): Either[Throwable, Unit] = {
    val before = state.get()
    val (next, admission) = PendingRegistryMachine.register(before.registry, key, entry.token, maxPending)
    admission match {
      case PendingRegistryMachine.Admission.Closed => Left(closedError())
      case PendingRegistryMachine.Admission.Duplicate => Left(new IllegalStateException("Duplicate browser request descriptor"))
      case PendingRegistryMachine.Admission.AtCapacity => Left(Frontend.ClientSideException("Too many pending browser requests"))
      case PendingRegistryMachine.Admission.Accepted =>
        if (state.compareAndSet(before, State(next, before.entries.updated(key, entry)))) Right(())
        else register(key, entry)
    }
  }

  @tailrec private def take(key: String, expected: Option[Entry]): Option[Entry] = {
    val before = state.get()
    val (next, taken) = PendingRegistryMachine.take(before.registry, key, expected.map(_.token))
    taken match {
      case None => None
      case Some(_) =>
        if (state.compareAndSet(before, State(next, before.entries - key))) before.entries.get(key)
        else take(key, expected)
    }
  }

  private def finish(key: String, expected: Option[Entry], result: Either[Throwable, A]): Boolean =
    take(key, expected) match {
      case None => false
      case Some(entry) => entry.finish(result); true
    }

  /** The returned effect is the actual response wait, not the send future.
    * A stalled send therefore cannot hide expiry or connection closure. The
    * supplied predicate must also gate queued emission after asynchronous work.
    */
  def request(key: String, timeout: FiniteDuration, timeoutError: () => Throwable)(
    send: (() => Boolean) => F[Unit]
  ): F[A] = Effect[F].promise[A] { callback =>
    val entry = new Entry(callback)
    register(key, entry) match {
      case Left(error) => entry.finish(Left(error))
      case Right(_) =>
        def pending(): Boolean = PendingRegistryMachine.contains(state.get().registry, key, entry.token)
        try {
          val cancel = deadlines.schedule(timeout)(() => { finish(key, Some(entry), Left(timeoutError())); () })
          entry.install(cancel)
          if (pending()) {
            Effect[F].runAsync(Effect[F].delayAsync(send(() => pending()))) {
              case Left(error) => finish(key, Some(entry), Left(error)); ()
              case Right(_) => ()
            }
          }
        } catch { case NonFatal(error) => finish(key, Some(entry), Left(error)); () }
    }
  }

  def complete(key: String, result: Either[Throwable, A]): F[Boolean] =
    Effect[F].delay(finish(key, None, result))

  def close(): F[Unit] = Effect[F].delay {
    val before = state.getAndSet(State(PendingRegistryMachine.closed[String], Map.empty))
    before.entries.values.foreach { entry =>
      // Isolate completion callbacks so one faulty effect implementation cannot
      // prevent other requests from being detached and failed during teardown.
      try entry.finish(Left(closedError())) catch { case NonFatal(_) => () }
    }
  }

  private[internal] def pendingCount: Int = PendingRegistryMachine.size(state.get().registry)
}
