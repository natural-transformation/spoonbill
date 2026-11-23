package spoonbill.server.internal.services

import spoonbill.effect.Stream
import spoonbill.internal.Frontend

/** Explicit result of preparing one connection. Callers choose reload or a live
  * session from this value, not from a later registry lookup or from whether
  * input happened to be canceled.
  */
private[spoonbill] sealed trait SessionStart[F[_]]

private[spoonbill] object SessionStart {

  /** `outgoing` is the attached application's live output. `release` is
    * idempotent session cleanup for a response that will not be delivered or
    * whose transport has ended. It does not by itself mean the connection
    * should stay open.
    */
  final case class Attached[F[_]](outgoing: Stream[F, String], release: () => F[Unit]) extends SessionStart[F]

  /** Recognized recovery. Guard, attachment, and input cleanup already ran
    * when a guard had been acquired. The caller sends the finite reload frame.
    */
  final case class Reload[F[_]]() extends SessionStart[F]

  /** The view that this poll built. Its frames are the long-polling entry's,
    * and nobody else's: no other transport gets them.
    */
  final case class Owned[F[_]](frames: Stream[F, Frontend.OutgoingFrame], release: () => F[Unit]) extends SessionStart[F]

  /** A WebSocket that came after a poll built the view of its session. It gets
    * nothing and is closed; the view goes on.
    */
  final case class Refused[F[_]]() extends SessionStart[F]
}
