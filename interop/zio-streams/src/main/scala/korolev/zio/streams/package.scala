package spoonbill.zio

import spoonbill.effect.{Effect as SpoonbillEffect, Stream as SpoonbillStream}
import zio.{Cause, Chunk, Ref, RIO, ZIO, ZManaged}
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

    /**
     * Pulls the stream chunk by chunk, for as long as the managed scope is
     * open. The result ends when the stream ends. When the stream fails, that
     * `pull` and every later one fail with the error of the stream.
     */
    def toSpoonbill(implicit eff: SpoonbillEffect[F]): ZManaged[R, Throwable, SpoonbillStream[F, Seq[O]]] =
      for {
        zPull  <- stream.process
        failed <- Ref.make(Option.empty[Cause[Throwable]]).toManaged_
      } yield new ZSpoonbillStream(zPull, failed)
  }

  private[streams] class ZSpoonbillStream[R, O](
    zPull: ZIO[R, Option[Throwable], Chunk[O]],
    failed: Ref[Option[Cause[Throwable]]]
  )(implicit eff: SpoonbillEffect[RIO[R, *]])
      extends SpoonbillStream[RIO[R, *], Seq[O]] {

    // In the error channel `None` is the end of the stream and `Some` is its
    // failure. Only the end becomes a value; the failure stays a failure.
    // After a failed pull ZIO 1 answers the next one with the end. So the cause
    // is kept, and it stays the answer.
    def pull(): RIO[R, Option[Seq[O]]] =
      failed.get.flatMap {
        case Some(cause) => ZIO.halt(cause)
        case None        => zPull.optional.tapCause(cause => failed.set(Some(cause)).unless(cause.interruptedOnly))
      }

    def cancel(): RIO[R, Unit] =
      ZIO.dieMessage("Can't cancel ZStream from Spoonbill")
  }
}
