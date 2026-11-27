package spoonbill

import _root_.cats.effect.kernel.Concurrent
import _root_.fs2.{Stream => Fs2Stream}
import java.util.concurrent.atomic.AtomicReference
import spoonbill.effect.{Effect => SpoonbillEffect, Queue, Stream => SpoonbillStream}
import spoonbill.effect.syntax._
import scala.concurrent.ExecutionContext

object fs2 {

  implicit class Fs2StreamOps[F[_]: SpoonbillEffect: Concurrent, O](stream: Fs2Stream[F, O]) {

    /**
     * Runs the stream in the background and hands its values over through a
     * buffer of `bufferSize` values.
     *
     * The result ends when the stream ends. When the stream fails, the result
     * first gives the values that the stream emitted. Then every `pull` fails
     * with the error of the stream. `cancel` interrupts the stream and ends the
     * result.
     *
     * Every run of the returned effect converts the stream anew: it starts the
     * stream once more and gives a result of its own.
     */
    def toSpoonbill(bufferSize: Int = 1)(implicit ec: ExecutionContext): F[SpoonbillStream[F, O]] =
      // The effect builds what one conversion owns. Built outside of it, a second
      // run would feed the queue of the first.
      SpoonbillEffect[F].delayAsync {
        val queue                                = new Queue[F, O](bufferSize)
        val cancelToken: Either[Throwable, Unit] = Right(())
        // The pump sets the failure before it stops the queue. So a consumer that
        // reads it after the queue ended has taken every value of the stream.
        val failure = new AtomicReference(Option.empty[Throwable])

        val result = new SpoonbillStream[F, O] {
          def pull(): F[Option[O]] = queue.stream.pull().flatMap {
            // Only `cancel` closes the queue. Such an end stays an end.
            case None if !queue.isClosed => failure.get().fold(SpoonbillEffect[F].none[O])(SpoonbillEffect[F].fail)
            case other                   => SpoonbillEffect[F].pure(other)
          }
          def cancel(): F[Unit] = queue.stream.cancel()
        }

        SpoonbillEffect[F]
          .start(
            stream
              .interruptWhen(queue.cancelSignal.as(cancelToken))
              .evalMap(queue.enqueue)
              .compile
              .drain
              .recoverF { case error => SpoonbillEffect[F].delay(failure.set(Some(error))) }
              .flatMap(_ => queue.stop())
          )
          .as(result)
      }
  }

  implicit class SpoonbillStreamOps[F[_]: SpoonbillEffect, O](stream: SpoonbillStream[F, O]) {
    def toFs2: Fs2Stream[F, O] =
      Fs2Stream.unfoldEval(()) { _ =>
        stream
          .pull()
          .map(mv => mv.map(v => (v, ())))
      }
  }

}
