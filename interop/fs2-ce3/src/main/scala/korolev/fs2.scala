package spoonbill

import _root_.cats.effect.kernel.Concurrent
import _root_.fs2.{Stream => Fs2Stream}
import spoonbill.effect.{Effect => SpoonbillEffect, Queue, Stream => SpoonbillStream}
import spoonbill.effect.syntax._
import scala.concurrent.ExecutionContext

object fs2 {

  implicit class Fs2StreamOps[F[_]: SpoonbillEffect: Concurrent, O](stream: Fs2Stream[F, O]) {

    def toSpoonbill(bufferSize: Int = 1)(implicit ec: ExecutionContext): F[SpoonbillStream[F, O]] = {
      val queue                                = new Queue[F, O](bufferSize)
      val cancelToken: Either[Throwable, Unit] = Right(())

      SpoonbillEffect[F]
        .start(
          stream
            .interruptWhen(queue.cancelSignal.as(cancelToken))
            .evalMap(queue.enqueue)
            .compile
            .drain
            .flatMap(_ => queue.stop())
        )
        .as(queue.stream)
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
