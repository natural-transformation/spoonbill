package spoonbill.security.jdbc

import java.sql.Connection
import javax.sql.DataSource
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.effect.Effect
import spoonbill.security.transaction.{Direct, OperationProtocolException, TransactionExecutor, TransactionFailure,
  TransactionProgram, TransactionScopePolicy}

/** Standalone outermost JDBC transaction runner. Compose host SQL in body;
  * never wrap this runner in another transaction. Supply a blocking executor.
  */
final class JdbcTransactionExecutor[F[_]](
  dataSource: DataSource,
  blockingExecutionContext: ExecutionContext
)(using effect: Effect[F]) extends TransactionExecutor[F, Direct, Connection] {
  override val program: TransactionProgram[Direct] = TransactionProgram.direct
  override val scopePolicy: TransactionScopePolicy = TransactionScopePolicy.ThreadConfined

  def transact[A](body: Connection => Direct[A]): F[Either[TransactionFailure, A]] =
    effect.blocking {
      JdbcTransactions.run[TransactionFailure, A](dataSource, TransactionFailure.StorageFailure, TransactionFailure.CommitUnknown) { connection =>
        try Right(body(connection)) catch {
          case error: OperationProtocolException => Left(TransactionFailure.Rejected(error.error))
          case NonFatal(_) => Left(TransactionFailure.RolledBack)
        }
      }
    }(blockingExecutionContext)
}
