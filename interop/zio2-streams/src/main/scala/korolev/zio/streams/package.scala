package spoonbill.zio

import spoonbill.effect.{Effect as SpoonbillEffect, Stream as SpoonbillStream}
import zio._
import zio.stream.ZStream

package object streams {

  implicit class SpoonbillStreamOps[R, O](stream: SpoonbillStream[RIO[R, *], O]) {

    def toZStream: ZStream[R, Throwable, O] =
      ZStream.unfoldZIO(()) { _ =>
        stream
          .pull()
          .map(mv => mv.map(v => (v, ())))
      }
  }

  private type Finalizer = Exit[Any, Any] => UIO[Unit]

  implicit class ZStreamOps[R, O](stream: ZStream[R, Throwable, O]) {

    def toSpoonbill(implicit eff: SpoonbillEffect[RIO[R, *]]): RIO[R, ZSpoonbillStream[R, O]] = {
      Scope.make.flatMap { scope =>
        scope.use[R](
          for {
            pull    <- stream.toPull
            zStream = ZSpoonbillStream[R, O](pull, scope.close(_))
          } yield zStream
        )
      }
    }
  }

  private[streams] case class ZSpoonbillStream[R, O](
    zPull: ZIO[R, Option[Throwable], Chunk[O]],
    finalizer: Finalizer
  )(implicit eff: SpoonbillEffect[RIO[R, *]])
      extends SpoonbillStream[RIO[R, *], Seq[O]] {

    def pull(): RIO[R, Option[Seq[O]]] =
      zPull.option

    def cancel(): RIO[R, Unit] =
      finalizer(Exit.unit)
  }
}
