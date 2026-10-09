package spoonbill.server

import java.util.concurrent.atomic.AtomicReference
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.syntax.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Successful WebSocket outcome. The variant is the connection disposition.
  *
  * Service failure, including access denial, stays in the effect error channel.
  * It is not a successful terminal response. Frame bytes never select a mode:
  * wrappers preserve the variant with [[WebSocketResponse.mapOutput]] or
  * [[WebSocketResponse.withOutput]].
  *
  * The adapter owns the physical transport. The core owns application and
  * session resources. During setup, the adapter-provided request input must
  * allow application reads to be canceled without closing the underlying
  * transport before this disposition is known. `release` is that boundary: call
  * and execute it when the response will not be delivered, or when the
  * transport has ended, and do not pull `output` after an abort. It is
  * idempotent. A normal duplex session keeps its input until the peer,
  * transport, or application ends it; do not call `release` merely because the
  * response was constructed.
  *
  * Handoff is race-safe only if a race cannot change the chosen variant.
  * Authorization failure, output failure, and peer termination must not drain
  * queued output that shutdown requires suppressing.
  */
sealed trait WebSocketResponse[F[_]] {
  def output: Stream[F, Bytes]
  def selectedProtocol: String

  /** Execute to drop this response and release its session resources. */
  def release: () => F[Unit]

  def mapOutput(f: Stream[F, Bytes] => Stream[F, Bytes])(implicit effect: Effect[F]): WebSocketResponse[F]

  def withOutput(output: Stream[F, Bytes])(implicit effect: Effect[F]): WebSocketResponse[F]
}

object WebSocketResponse {

  /** One cleanup attempt, decided when the effect runs.
    *
    * Applying the thunk does not claim ownership for a lazy effect. An eager
    * effect still starts when the thunk is applied, because that is when the
    * effect executes. The attempt runs in that same effect, so fiber-local
    * context is still visible. Replaying one description, or calling the thunk
    * again, waits for that same attempt. A non-fatal cleanup failure is
    * recovered and is not retried. Concurrent callers all observe that single
    * result. External cancellation is deferred until the attempt has finished,
    * so interrupting the owner cannot strand subsequent callers.
    */
  def releaseOnce[F[_]: Effect](effect: () => F[Unit]): () => F[Unit] = {
    sealed trait State
    case object Idle                                                       extends State
    final case class Running(waiters: List[Either[Throwable, Unit] => Unit]) extends State
    final case class Done(result: Either[Throwable, Unit])                 extends State

    val state = new AtomicReference[State](Idle)

    def publish(owner: Either[Throwable, Unit] => Unit, result: Either[Throwable, Unit]): Unit =
      state.getAndSet(Done(result)) match {
        case Running(pending) =>
          owner(result)
          pending.foreach(_(result))
        case _ => ()
      }

    () =>
      Effect[F].uncancelable {
        Effect[F].promiseF { callback =>
          @tailrec def claim(): Boolean =
            state.get match {
              case Done(result) =>
                callback(result)
                false
              case running: Running =>
                if (!state.compareAndSet(running, Running(callback :: running.waiters))) claim()
                else false
              case Idle =>
                if (!state.compareAndSet(Idle, Running(Nil))) claim()
                else true
            }
          if (claim()) {
            // Construction failures are recovered here so a throw while building
            // the cleanup effect still publishes one result to waiters.
            val cleanup =
              try effect().recover { case NonFatal(_) => () }
              catch { case NonFatal(_) => Effect[F].unit }
            cleanup.map { _ => publish(callback, Right(())) }
          } else Effect[F].unit
        }
      }
  }

  /** Cancel `transformed`, then run `previous`, even when cancel's effect cannot be built. */
  private def releasing[F[_]: Effect](
    transformed: Stream[F, Bytes],
    previous: () => F[Unit]
  ): () => F[Unit] =
    releaseOnce { () =>
      val cancelTransformed =
        try transformed.cancel()
        catch { case NonFatal(_) => Effect[F].unit }
      cancelTransformed.recover { case NonFatal(_) => () }.flatMap { _ =>
        try previous()
        catch { case NonFatal(error) => Effect[F].fail(error) }
      }
    }

  /** The application owns an active input and output session.
    *
    * Keep ordinary coupled termination: canceling application input, revocation,
    * or a peer or transport failure ends the connection. An already-canceled
    * input still terminates this session. Never treat that as [[SendThenClose]].
    */
  final case class Duplex[F[_]](
    output: Stream[F, Bytes],
    selectedProtocol: String,
    release: () => F[Unit]
  ) extends WebSocketResponse[F] {
    override def mapOutput(f: Stream[F, Bytes] => Stream[F, Bytes])(implicit effect: Effect[F]): Duplex[F] = {
      val transformed = f(output)
      copy(output = transformed, release = releasing(transformed, release))
    }

    override def withOutput(output: Stream[F, Bytes])(implicit effect: Effect[F]): Duplex[F] =
      copy(output = output, release = releasing(output, release))
  }

  /** The application has no further use for request input.
    *
    * Send finite `output` in order, then close normally. Disposing of
    * application input must not cancel `output`; the adapter owns a temporary
    * inbound drain that is independent of that input. Backpressure from the
    * peer applies to `output`. Do not buffer the whole response. Stalled peers
    * use the transport's existing completion limits.
    *
    * Normal completion closes the connection. Output failure or peer
    * termination stops promptly and does not continue into queued output.
    */
  final case class SendThenClose[F[_]](
    output: Stream[F, Bytes],
    selectedProtocol: String,
    release: () => F[Unit]
  ) extends WebSocketResponse[F] {
    override def mapOutput(f: Stream[F, Bytes] => Stream[F, Bytes])(implicit effect: Effect[F]): SendThenClose[F] = {
      val transformed = f(output)
      copy(output = transformed, release = releasing(transformed, release))
    }

    override def withOutput(output: Stream[F, Bytes])(implicit effect: Effect[F]): SendThenClose[F] =
      copy(output = output, release = releasing(output, release))
  }
}
