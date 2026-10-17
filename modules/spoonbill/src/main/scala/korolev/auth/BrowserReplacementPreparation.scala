package spoonbill.auth

import java.util.UUID
import spoonbill.effect.Effect
import spoonbill.security.transaction.{OperationError, TransactionExecutor, TransactionFailure}

/**
 * Settlement interpreter for authenticated replacement. Local capability
 * admission is followed by the provider's durable ceremony admission. The stage
 * callback must recheck the original predecessor, join host mutation and
 * structural successor/reservation validation in the supplied transaction, and
 * return only the provider completion ID. This class performs no host work or
 * storage checks itself and never grants ordinary application authority.
 *
 * A receipt belongs to the preparation invocation, not the earlier invocation
 * that began the ceremony. Failed or uncertain settlement returns no handoff;
 * recovery must resolve the same durable attempt without rerunning stage.
 */
final class BrowserReplacementPreparation[F[_], G[_], Tx, P](
  issuer: BrowserCapabilityScope[P],
  executor: TransactionExecutor[F, G, Tx],
  admit: (BrowserReplacementCeremony, BrowserReplacementInvocation) => F[Either[TransactionFailure, Unit]]
)(using F: Effect[F]) {
  def prepare(ceremony: BrowserReplacementCeremony, invocation: BrowserReplacementInvocation)(
    stage: Tx => G[UUID]
  ): F[Either[TransactionFailure, CommittedReplacement]] =
    F.flatMap(F.delay(issuer.admitReplacement(ceremony, invocation))) {
      case false => F.pure(Left(TransactionFailure.Rejected(OperationError.PermitUsed)))
      case true =>
        F.flatMap(F.delayAsync(admit(ceremony, invocation))) {
          case Left(error) => F.pure(Left(error))
          case Right(_) =>
            F.map(BrowserPreparationSettlement.once(executor)(stage))(
              _.map(completionId => issuer.committedReplacement(ceremony, invocation, completionId))
            )
        }
    }
}
