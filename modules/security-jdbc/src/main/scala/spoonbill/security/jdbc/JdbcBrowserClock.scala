package spoonbill.security.jdbc

import java.sql.Connection
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import scala.util.Using

/**
 * Durable nondecreasing clock for expiry observations. Its checkpoint commits
 * independently of the domain transaction, so a rejected authorization cannot
 * roll back observed expiry. A new instance always consults storage. Only an
 * explicit exclusive clock writer may reuse a locally acknowledged checkpoint;
 * shared writers consult storage on every call. After its first exact
 * checkpoint, an exclusive writer commits each checkpoint one lease ahead of the
 * observation it returns. Later observations within the lease reuse it without
 * JDBC work, and every returned time stays at or below an acknowledged durable
 * checkpoint, so a restarted instance never returns an earlier time. After a
 * quick restart, observed time can run up to one lease ahead of the system clock.
 * An exclusive writer commits its checkpoints one at a time, so concurrent
 * callers crossing a lease share one renewal. A caller waits at most five
 * seconds for another caller's checkpoint, then fails; a caller that waited
 * resamples time. A provider may also renew the lease ahead of expiry from its
 * own thread with renewAhead, so that callers perform no JDBC work in the
 * normal case. Reserve a clock connection or provide enough pool capacity for
 * domain work plus checkpoints.
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
  // Serializes an exclusive writer's checkpoints within this instance. A lock,
  // not a monitor: waiting is bounded and does not pin virtual threads.
  private val renewal = new ReentrantLock()
  // Guarded by renewal: when the last checkpoint attempt failed (System.nanoTime).
  private var failedAt = Option.empty[Long]
  // Set by every observation and consumed by a renewal ahead of expiry, so an
  // idle clock commits nothing in the background.
  private val demand = new AtomicBoolean()

  def initialize(connection: Connection): Unit = Using.resource(connection.createStatement()) { statement =>
    statement.executeUpdate("""CREATE TABLE IF NOT EXISTS spoonbill_auth_clock (
      realm VARCHAR(96) NOT NULL, namespace VARCHAR(96) NOT NULL, epoch_second BIGINT NOT NULL,
      nano INTEGER NOT NULL CHECK(nano>=0 AND nano<1000000000), PRIMARY KEY(realm,namespace))""")
    ()
  }

  def now(): Instant = {
    demand.set(true)
    // The first checkpoint of an instance is exact and adopts any newer stored
    // time. It completes before any observation is sampled, so no caller can
    // return a time earlier than that adopted floor.
    if (exclusiveClockWriter && acknowledged.get().isEmpty) exclusively {
      if (acknowledged.get().isEmpty) adopt(persist(sample()))
      ()
    }
    val candidate = sample()
    if (!exclusiveClockWriter) adopt(persist(candidate))
    else
      covered(candidate).getOrElse(exclusively {
        // Sample again after waiting, so expiry is judged at the returned time.
        // A checkpoint committed meanwhile may already cover it.
        val current = sample()
        covered(current).getOrElse(renew(current))
      })
  }

  /**
   * Renew the lease ahead of its expiry, from a thread the provider owns: when
   * an observation happened since the last renewal and the wall clock is within
   * half a lease of the checkpoint, commit a checkpoint one lease ahead of the
   * wall clock, without waiting for a renewal already in progress. Callers then
   * find an acknowledged checkpoint and do no JDBC work; should this fall
   * behind, a caller past the lease still renews inside its own transaction as
   * before. The decision is taken under the renewal lock and from the wall
   * clock alone: a renewal completed meanwhile is not repeated or adopted, and
   * a future checkpoint adopted after a restart is not extended until wall time
   * approaches it, so repeated restarts cannot push observed time further
   * ahead. Returns whether a checkpoint was committed. Only an exclusive writer
   * renews ahead.
   */
  def renewAhead(): Boolean =
    exclusiveClockWriter && demand.getAndSet(false) && renewal.tryLock() && {
      try
        acknowledged.get().exists { checkpoint =>
          val wall = clock()
          !wall.isBefore(checkpoint.minus(JdbcBrowserClock.RenewAheadWindow)) && { renew(wall); true }
        }
      catch {
        case _: JdbcAuthException =>
          failedAt = Some(System.nanoTime())
          false
      } finally renewal.unlock()
    }

  /**
   * Run one checkpoint step at a time, waiting a bounded time for the lock. A
   * caller that waited across a failed checkpoint fails as well, instead of
   * every waiting caller retrying in turn during an outage.
   */
  private def exclusively[A](step: => A): A = {
    val waiting = System.nanoTime()
    if (!renewal.tryLock(JdbcBrowserClock.RenewalWait.toNanos, TimeUnit.NANOSECONDS))
      throw new JdbcAuthException(JdbcAuthError.StorageFailure)
    try {
      if (failedAt.exists(time => time - waiting > 0)) throw new JdbcAuthException(JdbcAuthError.StorageFailure)
      try step
      catch {
        case error: JdbcAuthException =>
          failedAt = Some(System.nanoTime())
          throw error
      }
    } finally renewal.unlock()
  }

  /** Commit a checkpoint one lease ahead of `current` and acknowledge it; the time to observe. Under the lock. */
  private def renew(current: Instant): Instant = {
    val target    = current.plus(JdbcBrowserClock.ExclusiveCheckpointLease)
    val persisted = persist(target)
    acknowledged.updateAndGet(previous => Some(previous.filter(_.isAfter(persisted)).getOrElse(persisted)))
    // Checkpoints are serialized here, so only another writer can store a
    // later time; observe it as before.
    if (persisted.isAfter(target)) adopt(persisted) else current
  }

  private def sample(): Instant = {
    val sampled = clock()
    observed.updateAndGet(previous => if (sampled.isAfter(previous)) sampled else previous)
  }

  /** The observation itself when an acknowledged durable checkpoint covers it. */
  private def covered(candidate: Instant): Option[Instant] =
    acknowledged.get().filter(checkpoint => !candidate.isAfter(checkpoint)).map(_ => candidate)

  /** Observe the stored maximum: later calls never return an earlier time. */
  private def adopt(persisted: Instant): Instant = {
    observed.updateAndGet(previous => if (persisted.isAfter(previous)) persisted else previous)
    acknowledged.updateAndGet(previous => Some(previous.filter(_.isAfter(persisted)).getOrElse(persisted))).get
  }

  /** Commit a checkpoint of at least `candidate`; returns the stored maximum. */
  private def persist(candidate: Instant): Instant = {
    JdbcTransactions
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
  }
}

object JdbcBrowserClock {

  /** How far ahead an exclusive writer checkpoints: at most one commit per lease. */
  private val ExclusiveCheckpointLease = java.time.Duration.ofSeconds(1)

  /** How long a caller waits for another caller's checkpoint before failing. */
  private val RenewalWait = java.time.Duration.ofSeconds(5)

  /** A renewal ahead of expiry runs once at most this much of the lease remains. */
  private val RenewAheadWindow = java.time.Duration.ofMillis(500)

  /** How often a provider's renewal thread should call renewAhead: well within the window. */
  private[jdbc] val RenewAheadPeriod = java.time.Duration.ofMillis(250)
}
