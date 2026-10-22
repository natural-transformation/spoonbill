package spoonbill.effect.io

import spoonbill.effect.{Close, Effect, Stream}
import spoonbill.effect.io.DataSocket.CloseReason
import spoonbill.effect.syntax._

trait DataSocket[F[_], B] {
  def stream: Stream[F, B]
  def onClose(): F[CloseReason]
  def write(bytes: B): F[Unit]
  /** Close the transport. Custom implementations retain their cancellation fallback. */
  def shutdown(): F[Unit] = stream.cancel()
}

object DataSocket {

  sealed trait CloseReason

  object CloseReason {
    case object ByPeer             extends CloseReason
    case object StreamCanceled     extends CloseReason
    case class Error(e: Throwable) extends CloseReason
  }

  implicit def dataSocketClose[F[_]: Effect, B]: Close[F, DataSocket[F, B]] =
    new Close[F, DataSocket[F, B]] {
      def onClose(that: DataSocket[F, B]): F[Unit] =
        that.onClose().unit
      def close(that: DataSocket[F, B]): F[Unit] =
        that.shutdown()
    }
}
