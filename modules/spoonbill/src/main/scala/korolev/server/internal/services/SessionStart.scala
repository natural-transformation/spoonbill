package spoonbill.server.internal.services

import spoonbill.effect.Stream

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
}
