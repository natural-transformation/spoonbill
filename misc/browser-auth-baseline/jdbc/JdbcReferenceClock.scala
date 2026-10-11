package spoonbill.security.jdbc.baseline

import java.sql.{Connection, SQLException}
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import scala.util.Using
import spoonbill.security.jdbc.JdbcTransactions

object JdbcReferenceClock {
  enum Failure { case StorageFailure, CommitUnknown }
  final class ClockException(val failure: Failure) extends SQLException(s"Durable clock checkpoint: $failure")
}

/**
 * Single-writer baseline clock infrastructure. Each new observed time is
 * durably acknowledged before it is returned, independently of domain rollback.
 * A fresh process always reads/checkpoints storage before using its clock.
 * Seconds plus nanos preserve Instant exactly, without database timestamp
 * rounding.
 */
final class JdbcReferenceClock(source: DataSource, realm: String, namespace: String, clock: () => Instant) {
  private val acknowledged = new AtomicReference(Option.empty[Instant])
  // A failed checkpoint cannot make a later local retry forget its candidate;
  // this value is never exposed until durable acknowledgment.
  private val pending = new AtomicReference(Instant.MIN)

  def initialize(connection: Connection): Unit = Using.resource(connection.createStatement()) { query =>
    query.executeUpdate(
      "CREATE TABLE baseline_clock(realm TEXT NOT NULL, namespace TEXT NOT NULL, epoch_second BIGINT NOT NULL, " +
        "nano INTEGER NOT NULL CHECK(nano>=0 AND nano<1000000000), PRIMARY KEY(realm,namespace))"
    )
    ()
  }

  def now(): Instant = {
    val candidate = clock()
    val observed  = pending.updateAndGet(previous => if (candidate.isAfter(previous)) candidate else previous)
    acknowledged.get().filter(previous => !observed.isAfter(previous)).getOrElse {
      val persisted = JdbcTransactions
        .run[JdbcReferenceClock.Failure, Instant](
          source,
          JdbcReferenceClock.Failure.StorageFailure,
          JdbcReferenceClock.Failure.CommitUnknown
        ) { connection =>
          val value = Using.resource(
            connection.prepareStatement(
              "INSERT INTO baseline_clock(realm,namespace,epoch_second,nano) VALUES (?,?,?,?) " +
                "ON CONFLICT(realm,namespace) DO UPDATE SET " +
                "epoch_second=CASE WHEN (EXCLUDED.epoch_second,EXCLUDED.nano) > (baseline_clock.epoch_second,baseline_clock.nano) " +
                "THEN EXCLUDED.epoch_second ELSE baseline_clock.epoch_second END, " +
                "nano=CASE WHEN (EXCLUDED.epoch_second,EXCLUDED.nano) > (baseline_clock.epoch_second,baseline_clock.nano) " +
                "THEN EXCLUDED.nano ELSE baseline_clock.nano END RETURNING epoch_second,nano"
            )
          ) { query =>
            query.setString(1, realm); query.setString(2, namespace)
            query.setLong(3, observed.getEpochSecond); query.setInt(4, observed.getNano)
            Using.resource(query.executeQuery()) { rows =>
              if (!rows.next()) throw new IllegalStateException("Durable clock checkpoint missing")
              Instant.ofEpochSecond(rows.getLong(1), rows.getInt(2).toLong)
            }
          }
          Right(value)
        }
        .fold(error => throw new JdbcReferenceClock.ClockException(error), identity)
      acknowledged.updateAndGet(previous => Some(previous.filter(_.isAfter(persisted)).getOrElse(persisted))).get
    }
  }
}
