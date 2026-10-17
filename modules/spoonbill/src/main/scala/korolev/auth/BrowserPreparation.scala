package spoonbill.auth

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.effect.Effect
import spoonbill.security.transaction.{
  OperationError,
  OperationProtocolException,
  TransactionExecutor,
  TransactionFailure
}

/**
 * Settlement interpreter shared by browser providers. Admission is the
 * provider's existing ceremony fence, not another proof or operation ledger.
 * The stage callback composes host mutation and browser outcome in G using only
 * the executor's transaction. Its executor owns native cancellation, rollback,
 * commit and resource finalization; this class never settles it.
 *
 * Both callback construction and lazy G evaluation are guarded. Re-evaluating
 * an already evaluated program cannot repeat host proof consumption. A receipt
 * is promoted only from acknowledged outermost commit, never from a staged
 * callback value, an interrupted observer or uncertain settlement.
 */
final class BrowserPreparation[F[_], G[_], Tx, P](
  issuer: BrowserCapabilityScope[P],
  executor: TransactionExecutor[F, G, Tx],
  admit: BrowserCeremony => F[Either[TransactionFailure, Unit]]
)(using F: Effect[F]) {
  def prepare(ceremony: BrowserCeremony)(stage: Tx => G[UUID]): F[Either[TransactionFailure, CommittedAuthentication]] =
    F.flatMap(F.delay(issuer.admit(ceremony))) {
      case false => F.pure(Left(TransactionFailure.Rejected(OperationError.PermitUsed)))
      case true =>
        F.flatMap(F.delayAsync(admit(ceremony))) {
          case Left(error) => F.pure(Left(error))
          case Right(_) =>
            F.map(BrowserPreparationSettlement.once(executor)(stage))(_.map(issuer.committed))
        }
    }
}

/**
 * Shared execution guard only: ordinary and replacement admission and receipt
 * promotion remain distinct. The transaction executor still owns settlement.
 */
private[auth] object BrowserPreparationSettlement {
  def once[F[_], G[_], Tx, A](executor: TransactionExecutor[F, G, Tx])(
    stage: Tx => G[A]
  ): F[Either[TransactionFailure, A]] = {
    val entered = new AtomicBoolean()
    executor.transact { transaction =>
      executor.program.defer {
        if (!entered.compareAndSet(false, true))
          throw new OperationProtocolException(OperationError.PermitUsed)
        stage(transaction)
      }
    }
  }
}
