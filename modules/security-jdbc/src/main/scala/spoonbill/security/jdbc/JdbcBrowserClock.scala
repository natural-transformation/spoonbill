package spoonbill.security.jdbc

import java.sql.Connection
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import scala.util.Using

/**
 * Durable nondecreasing clock for expiry observations. Its checkpoint commits
 * independently of the domain transaction, so a rejected authorization cannot
 * roll back observed expiry. A new instance always consults storage. Only an
 * explicit exclusive clock writer may reuse a locally acknowledged checkpoint
 * for equal/backward samples; shared writers consult storage on every call.
 * Reserve a clock connection or provide enough pool capacity for domain work
 * plus checkpoints.
 */
final class JdbcBrowserClock(
  source: DataSource,
  realm: String,
  namespace: String,
  clock: () => Instant,
  exclusiveClockWriter: Boolean = false
) {
  private val observed     = new AtomicReference(Instant.MIN)
  private val acknowledged = new AtomicReference(Option.empty[Instant])

  def initialize(connection: Connection): Unit = Using.resource(connection.createStatement()) { statement =>
    statement.executeUpdate("""CREATE TABLE IF NOT EXISTS spoonbill_auth_clock (
      realm VARCHAR(96) NOT NULL, namespace VARCHAR(96) NOT NULL, epoch_second BIGINT NOT NULL,
      nano INTEGER NOT NULL CHECK(nano>=0 AND nano<1000000000), PRIMARY KEY(realm,namespace))""")
    ()
  }

  def now(): Instant = {
    val sampled   = clock()
    val candidate = observed.updateAndGet(previous => if (sampled.isAfter(previous)) sampled else previous)
    val cached    = if (exclusiveClockWriter) acknowledged.get().filter(value => !candidate.isAfter(value)) else None
    cached.getOrElse {
      val persisted = JdbcTransactions
        .run[JdbcAuthError, Instant](source, JdbcAuthError.StorageFailure, JdbcAuthError.StorageFailure) { connection =>
          Using.resource(connection.prepareStatement("""INSERT INTO spoonbill_auth_clock(realm,namespace,epoch_second,nano)
        VALUES (?,?,?,?) ON CONFLICT(realm,namespace) DO UPDATE SET
        epoch_second=CASE WHEN (EXCLUDED.epoch_second,EXCLUDED.nano) > (spoonbill_auth_clock.epoch_second,spoonbill_auth_clock.nano)
          THEN EXCLUDED.epoch_second ELSE spoonbill_auth_clock.epoch_second END,
        nano=CASE WHEN (EXCLUDED.epoch_second,EXCLUDED.nano) > (spoonbill_auth_clock.epoch_second,spoonbill_auth_clock.nano)
          THEN EXCLUDED.nano ELSE spoonbill_auth_clock.nano END RETURNING epoch_second,nano""")) { query =>
            query.setString(1, realm); query.setString(2, namespace)
            query.setLong(3, candidate.getEpochSecond); query.setInt(4, candidate.getNano)
            Using.resource(query.executeQuery()) { rows =>
              if (!rows.next()) Left(JdbcAuthError.StorageFailure)
              else Right(Instant.ofEpochSecond(rows.getLong(1), rows.getInt(2).toLong))
            }
          }
        }
        .fold(error => throw new JdbcAuthException(error), identity)
      observed.updateAndGet(previous => if (persisted.isAfter(previous)) persisted else previous)
      acknowledged.updateAndGet(previous => Some(previous.filter(_.isAfter(persisted)).getOrElse(persisted))).get
    }
  }
}
