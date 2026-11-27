package spoonbill

import _root_.cats.effect.kernel.Concurrent
import _root_.fs2.{Stream => Fs2Stream}
import java.util.concurrent.CancellationException
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
     * with the error of the stream. A stream that is cancelled before its end
     * is told in the same way: after the emitted values every `pull` fails with
     * a `CancellationException`. An effect of the stream that cancels itself
     * cancels the stream with fs2 3.2.14 or later. An older fs2 never ends such
     * a stream, and then the result has no end either. `cancel` interrupts the
     * stream and ends the result.
     *
     * Every run of the returned effect converts the stream anew: it starts the
     * stream once more and gives a result of its own.
     */
    def toSpoonbill(bufferSize: Int = 1)(implicit ec: ExecutionContext): F[SpoonbillStream[F, O]] =
      // The effect builds what one conversion owns. Built outside of it, a second
      // run would feed the queue of the first.
      SpoonbillEffect[F].delay(new Queue[F, O](bufferSize)).flatMap { queue =>
        val cancelToken: Either[Throwable, Unit] = Right(())
        // However the pump ends, it tells the queue how the stream ended. The
        // queue keeps that end behind the values which it accepted. Only
        // `cancel` closes the queue, and a closed queue ends without a failure.
        val pump = stream
          .interruptWhen(queue.cancelSignal.as(cancelToken))
          .evalMap(queue.enqueue)
          .compile
          .drain
          .flatMap(_ => queue.stop())
          .recoverF { case error => queue.stopWithFailure(error) }
        val canceled = queue.stopWithFailure(new CancellationException("The fs2 stream was canceled before its end"))

        SpoonbillEffect[F].start(Concurrent[F].onCancel(pump, canceled)).as(queue.stream)
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
