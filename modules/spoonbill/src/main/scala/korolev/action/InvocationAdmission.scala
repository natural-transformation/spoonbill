package spoonbill.action

import spoonbill.effect.Effect

/** Runtime-owned freshness decision for one original browser invocation.
  * The final check and callback construction share one effect thunk: no framework
  * suspension or lock lies between them. A callback admitted by this decision
  * may already have committed effects when departure arrives later.
  */
private[spoonbill] final class InvocationAdmission(isCurrent: () => Boolean) {
  def invoke[F[_]: Effect, A](superseded: => A)(operation: => F[A]): F[A] =
    Effect[F].delayAsync {
      if (isCurrent()) operation else Effect[F].pure(superseded)
    }
}

private[spoonbill] object InvocationAdmission {
  val untracked: InvocationAdmission = new InvocationAdmission(() => true)
}
