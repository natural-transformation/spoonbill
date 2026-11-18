package spoonbill.http

import java.util.concurrent.atomic.AtomicReference
import spoonbill.effect.{Effect, Stream}

/** Ordinary HTTP reads never expose transport bytes. Only the validated public
  * WebSocket upgrade boundary may claim the transport during this request's
  * handler evaluation; stale or repeated upgrade attempts cannot reuse it.
  */
private[http] final class HttpUpgradeInput[F[_]: Effect, B](
  ordinary: Stream[F, B],
  transport: Stream[F, B]
) extends Stream[F, B] {
  private enum State { case Available, Claimed, Sealed }
  private val state = new AtomicReference(State.Available)

  def pull(): F[Option[B]] = ordinary.pull()
  def cancel(): F[Unit] = ordinary.cancel()

  private[http] def claim(): F[Stream[F, B]] = Effect[F].delay {
    if (!state.compareAndSet(State.Available, State.Claimed))
      throw new IllegalStateException("HTTP upgrade input is no longer available")
    transport
  }

  /** Seal after response construction; report whether an upgrade consumed input. */
  private[http] def seal(): Boolean = state.getAndSet(State.Sealed) == State.Claimed
}
