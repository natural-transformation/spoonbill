package spoonbill.zio

import spoonbill.effect.{Effect as SpoonbillEffect, Stream as SpoonbillStream}
import zio.{Chunk, RIO, ZIO, ZManaged}
import zio.stream.ZStream

package object streams {

  implicit class SpoonbillSreamOps[R, O](stream: SpoonbillStream[RIO[R, *], O]) {

    def toZStream: ZStream[R, Throwable, O] =
      ZStream.unfoldM(()) { _ =>
        stream
          .pull()
          .map(mv => mv.map(v => (v, ())))
      }
  }

  implicit class ZStreamOps[R, O](stream: ZStream[R, Throwable, O]) {

    type F[A] = RIO[R, A]

    def toSpoonbill(implicit eff: SpoonbillEffect[F]): ZManaged[R, Throwable, SpoonbillStream[F, Seq[O]]] =
      stream.process.map { zPull =>
        new ZSpoonbillStream(zPull)
      }
  }

  private[streams] class ZSpoonbillStream[R, O](
    zPull: ZIO[R, Option[Throwable], Chunk[O]]
  )(implicit eff: SpoonbillEffect[RIO[R, *]])
      extends SpoonbillStream[RIO[R, *], Seq[O]] {

    def pull(): RIO[R, Option[Seq[O]]] =
      zPull.option

    def cancel(): RIO[R, Unit] =
      ZIO.dieMessage("Can't cancel ZStream from Spoonbill")
  }
}
