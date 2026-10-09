package spoonbill.security.jdbc

import java.sql.Connection
import java.util.concurrent.Executor
import javax.sql.DataSource
import scala.util.control.NonFatal

/** Outermost transaction ownership and safe pool return, shared by JDBC adapters.
  * A connection is returned only after acknowledged commit/rollback or successful
  * abort. JDBC close on an unresolved transaction is implementation-defined and
  * a pool may commit it. If abort fails, deliberately do not close/reset/reuse the
  * connection: its resource disposition remains unresolved and requires the
  * owning pool's eviction/administrative recovery. Never retry the host callback.
  */
private[jdbc] object JdbcTransactions {
  private val directExecutor: Executor = new Executor {
    def execute(command: Runnable): Unit = command.run()
  }

  /** Never return unresolved work through a pool's normal close/reset path. */
  def dispose(connection: Connection, settled: Boolean): Unit = {
    // Successful abort marks the JDBC connection closed; the direct executor
    // also completes driver-scheduled physical cleanup before pool return.
    val safeToReturn = settled || (try {
      connection.abort(directExecutor)
      true
    } catch { case NonFatal(_) => false })
    if (safeToReturn) {
      // Cleanup failure cannot erase an acknowledged transaction outcome.
      try connection.close() catch { case NonFatal(_) => () }
    }
  }

  def run[E, A](source: DataSource, storageFailure: E, unresolved: E)(
    body: Connection => Either[E, A]
  ): Either[E, A] = try {
    val connection = source.getConnection
    // ALLOW-VAR: confined to this synchronous transaction owner; never shared with callbacks.
    var settled = false
    try {
      connection.setAutoCommit(false)
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED)
      val staged = try body(connection) catch { case NonFatal(_) => Left(storageFailure) }
      staged match {
        case Left(error) =>
          try {
            connection.rollback()
            settled = true
            Left(error)
          } catch { case NonFatal(_) => Left(unresolved) }
        case Right(value) =>
          try {
            connection.commit()
            settled = true
            Right(value)
          } catch { case NonFatal(_) => Left(unresolved) }
      }
    } finally dispose(connection, settled)
  } catch { case NonFatal(_) => Left(storageFailure) }
}
