package spoonbill.action

import spoonbill.effect.Effect

private[spoonbill] object StateUpdateInterpreter:
  def apply[F[_]: Effect, S](update: StateUpdate[S])(transition: (S => S) => F[Unit]): F[Unit] =
    update match
      case StateUpdate.NoUpdate()     => Effect[F].unit
      case StateUpdate.Modify(reduce) => transition(reduce)

  def callback[F[_]: Effect, S](update: => StateUpdate[S])(transition: (S => S) => F[Unit]): F[Unit] =
    val F = Effect[F]
    F.flatMap(F.delay(update))(value => apply(value)(transition))
