package spoonbill.security.jdbc

import java.sql.{Connection, PreparedStatement, Timestamp}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using
import spoonbill.auth.{BrowserIssuance, BrowserRetentionPolicy}

/** Reclamation runs under the realm lock; cadence reservations commit independently. */
private[jdbc] final class JdbcBrowserRetention(realm: String, namespace: String, policy: BrowserRetentionPolicy, vault: JdbcCredentialVault, clock: () => Instant, workSource: DataSource) {
  private val sweepInterval = Vector(policy.workIntervalSeconds, policy.idleBindingSeconds,
    policy.idleViewSeconds, policy.historySeconds).min

  def reserveSweep(time: Instant, pressure: Boolean): Boolean =
    reserve("retention", time, if (pressure) 1L else sweepInterval)

  def reserveCapacity(time: Instant): Boolean = reserve("capacity", time, policy.workIntervalSeconds)

  /**
   * A reservation is a retry backoff, never evidence that cleanup succeeded.
   * Commit it separately so a refused/rolled-back admission cannot repeatedly
   * dispatch a full scan. This table deliberately has no FK to uncommitted
   * enrollment metadata and is never locked by the domain transaction.
   */
  private def reserve(kind: String, time: Instant, seconds: Long): Boolean = {
    val cutoff = time.minusSeconds(seconds)
    JdbcTransactions.run[JdbcAuthError, Boolean](workSource, JdbcAuthError.StorageFailure, JdbcAuthError.StorageFailure) { connection =>
      Right(sql(connection,
        """INSERT INTO spoonbill_browser_work(realm,namespace,kind,epoch_second,nano) VALUES (?,?,?,?,?)
        ON CONFLICT(realm,namespace,kind) DO UPDATE SET epoch_second=EXCLUDED.epoch_second,nano=EXCLUDED.nano
        WHERE (spoonbill_browser_work.epoch_second,spoonbill_browser_work.nano)<=(?,?) RETURNING kind""") { query =>
        query.setString(3, kind); query.setLong(4, time.getEpochSecond); query.setInt(5, time.getNano)
        query.setLong(6, cutoff.getEpochSecond); query.setInt(7, cutoff.getNano)
        Using.resource(query.executeQuery())(_.next())
      })
    }.fold(error => throw new JdbcAuthException(error), identity)
  }
  private def associated(prefix: Long): Array[Byte] =
    s"spoonbill-browser-issuance-v1:${realm.length}:$realm${namespace.length}:$namespace:$prefix".getBytes(UTF_8)
  private def sql[A](connection: Connection, text: String)(run: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(text)) { query =>
      query.setString(1, realm); query.setString(2, namespace); run(query)
    }

  def initialize(connection: Connection): Unit = JdbcBrowserRetention.initialize(connection)

  def enable(connection: Connection): Unit = {
    val present = sql(connection, "SELECT id_prefix FROM spoonbill_browser_retention WHERE realm=? AND namespace=? FOR UPDATE") {
      query => Using.resource(query.executeQuery())(rows => if (rows.next()) Some(rows.getLong(1)) else None)
    }
    if (present.isEmpty) {
      // Older random ceremony/attempt IDs remain distinct from the new monotonic
      // namespace, even after their rows become eligible for reclamation.
      val existing = sql(connection, "SELECT id,attempt FROM spoonbill_auth_ceremony WHERE realm=? AND namespace=?") { query =>
        Using.resource(query.executeQuery()) { rows =>
          val prefixes = Set.newBuilder[Long]
          while (rows.next()) {
            prefixes += rows.getObject(1, classOf[UUID]).getMostSignificantBits
            prefixes += rows.getObject(2, classOf[UUID]).getMostSignificantBits
          }
          prefixes.result()
        }
      }
      var prefix = BrowserIssuance.newPrefix()
      while (existing.contains(prefix)) prefix = BrowserIssuance.newPrefix()
      val key = BrowserIssuance.newKey()
      val encrypted = try vault.seal(key, associated(prefix)) finally java.util.Arrays.fill(key, 0.toByte)
      sql(connection, "INSERT INTO spoonbill_browser_retention(realm,namespace,id_prefix,credential_key) VALUES (?,?,?,?)") { query =>
        query.setLong(3, prefix); query.setBytes(4, encrypted); query.executeUpdate(); ()
      }
      // Enrollment gives legacy sessions the same finite upper lifetime as new
      // sessions. Preserve host identity stamps; only shorten browser authority.
      execute(connection, """WITH bounds AS (SELECT ?::varchar AS realm,?::varchar AS namespace,?::timestamptz AS deadline)
        UPDATE spoonbill_browser_session s SET expires_at=LEAST(s.expires_at,b.deadline) FROM bounds b
        WHERE s.realm=b.realm AND s.cookie_namespace=b.namespace""", clock().plusSeconds(policy.maxSessionSeconds))
    }
  }

  def credentialKey(connection: Connection): Array[Byte] = sql(connection,
    "SELECT id_prefix,credential_key FROM spoonbill_browser_retention WHERE realm=? AND namespace=?") { query =>
    Using.resource(query.executeQuery()) { rows =>
      if (!rows.next()) throw new JdbcAuthException(JdbcAuthError.StorageFailure)
      val key = vault.open(rows.getBytes(2), associated(rows.getLong(1)))
      if (key.length != 32) {
        java.util.Arrays.fill(key, 0.toByte)
        throw new JdbcAuthException(JdbcAuthError.StorageFailure)
      }
      key
    }
  }

  def identifier(connection: Connection): UUID = sql(connection,
    """UPDATE spoonbill_browser_retention SET id_counter=id_counter+1
    WHERE realm=? AND namespace=? AND id_counter<4611686018427387903 RETURNING id_prefix,id_counter""") { query =>
    Using.resource(query.executeQuery()) { rows =>
      if (!rows.next()) throw new JdbcAuthException(JdbcAuthError.GenerationExhausted)
      BrowserIssuance.identifier(rows.getLong(1), rows.getLong(2))
    }
  }

  private def floor(connection: Connection, column: String): Long =
    sql(connection, s"SELECT $column FROM spoonbill_browser_retention WHERE realm=? AND namespace=?") { query =>
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) throw new JdbcAuthException(JdbcAuthError.StorageFailure)
        rows.getLong(1)
      }
    }
  def bindingFloor(connection: Connection): Long = floor(connection, "binding_floor")
  def viewFloor(connection: Connection): Long = floor(connection, "view_floor")

  private def execute(connection: Connection, text: String, deadlines: Instant*): Unit = sql(connection, text) { query =>
    deadlines.zipWithIndex.foreach { case (time, index) => query.setTimestamp(index + 3, Timestamp.from(time)) }
    query.executeUpdate(); ()
  }

  /** Expiry + writer exclusion + permanent floors, never expiry alone. */
  def reap(connection: Connection, time: Instant): Unit = {
    val history = time.minusSeconds(policy.historySeconds)
    val viewCut = time.minusSeconds(policy.idleViewSeconds)
    val bindingCut = time.minusSeconds(policy.idleBindingSeconds)

    // An expired prepared replacement must never revive its still-live
    // predecessor. Expired active sessions also advance their slot fence.
    execute(connection, """UPDATE spoonbill_browser_slot b SET generation=b.generation+1,current_session_id=NULL
      WHERE b.realm=? AND b.cookie_namespace=? AND b.generation<9223372036854775807 AND (
      EXISTS (SELECT 1 FROM spoonbill_browser_session s WHERE s.realm=b.realm AND s.cookie_namespace=b.cookie_namespace
        AND s.host_session_id=b.current_session_id AND s.expires_at<=?
        AND NOT EXISTS (SELECT 1 FROM spoonbill_browser_replacement r WHERE r.realm=b.realm AND r.namespace=b.cookie_namespace
          AND r.binding_hash=b.binding_hash AND r.generation=b.generation AND r.successor_session_id IS NOT NULL)) OR
      EXISTS (SELECT 1 FROM spoonbill_browser_replacement r JOIN spoonbill_browser_session s
        ON s.realm=r.realm AND s.cookie_namespace=r.namespace AND s.host_session_id=r.successor_session_id
        WHERE r.realm=b.realm AND r.namespace=b.cookie_namespace AND r.binding_hash=b.binding_hash
          AND r.generation=b.generation AND r.successor_session_id IS NOT NULL
          AND b.current_session_id=r.predecessor_session_id AND s.expires_at<=?))""", time, time)

    execute(connection, """UPDATE spoonbill_browser_view v SET owner_id=NULL,
      epoch=CASE WHEN epoch<9223372036854775807 THEN epoch+1 ELSE epoch END
      WHERE v.realm=? AND v.cookie_namespace=? AND v.owner_id IS NOT NULL AND
      (v.last_seen<=? OR EXISTS (SELECT 1 FROM spoonbill_browser_session s JOIN spoonbill_browser_slot b
        ON b.realm=s.realm AND b.cookie_namespace=s.cookie_namespace AND b.binding_hash=s.binding_hash
        WHERE s.realm=v.realm AND s.cookie_namespace=v.cookie_namespace AND s.binding_hash=v.binding_hash
        AND s.active_generation IS NOT NULL AND b.current_session_id IS NULL AND s.expires_at<=?))""", viewCut, time)

    execute(connection, """DELETE FROM spoonbill_auth_material WHERE realm=? AND namespace=? AND expires_at<=?""", time)
    execute(connection, """DELETE FROM spoonbill_browser_replacement r WHERE r.realm=? AND r.namespace=?
      AND EXISTS (SELECT 1 FROM spoonbill_auth_ceremony c WHERE c.realm=r.realm AND c.namespace=r.namespace
        AND c.id=r.ceremony_id AND c.expires_at<=?)
      AND (r.successor_session_id IS NULL OR NOT EXISTS (SELECT 1 FROM spoonbill_browser_session s
        WHERE s.realm=r.realm AND s.cookie_namespace=r.namespace AND s.expires_at>?
        AND s.host_session_id IN (r.predecessor_session_id,r.successor_session_id)))""", history, history)

    val expiredSession = """s.expires_at<=? AND NOT EXISTS (SELECT 1 FROM spoonbill_browser_replacement r
      WHERE r.realm=s.realm AND r.namespace=s.cookie_namespace
      AND s.host_session_id IN (r.predecessor_session_id,r.successor_session_id))"""
    execute(connection, s"""DELETE FROM spoonbill_auth_material m USING spoonbill_browser_completion c,spoonbill_browser_session s
      WHERE m.realm=? AND m.namespace=? AND c.realm=m.realm AND c.cookie_namespace=m.namespace AND c.completion_id=m.attempt
      AND s.realm=c.realm AND s.cookie_namespace=c.cookie_namespace AND s.token_hash=c.token_hash AND $expiredSession""", history)
    execute(connection, s"""DELETE FROM spoonbill_browser_completion c USING spoonbill_browser_session s
      WHERE c.realm=? AND c.cookie_namespace=? AND s.realm=c.realm AND s.cookie_namespace=c.cookie_namespace
      AND s.token_hash=c.token_hash AND $expiredSession""", history)
    execute(connection, s"""DELETE FROM spoonbill_browser_session s WHERE s.realm=? AND s.cookie_namespace=? AND $expiredSession""", history)
    execute(connection, """DELETE FROM spoonbill_auth_ceremony c WHERE c.realm=? AND c.namespace=? AND c.expires_at<=?
      AND NOT EXISTS (SELECT 1 FROM spoonbill_browser_completion b WHERE b.realm=c.realm AND b.cookie_namespace=c.namespace AND b.completion_id=c.attempt)
      AND NOT EXISTS (SELECT 1 FROM spoonbill_auth_material m WHERE m.realm=c.realm AND m.namespace=c.namespace AND m.attempt=c.attempt)
      AND NOT EXISTS (SELECT 1 FROM spoonbill_browser_replacement r WHERE r.realm=c.realm AND r.namespace=c.namespace AND r.ceremony_id=c.id)""", history)

    val expiredView = """v.owner_id IS NULL AND v.last_seen<=? AND v.epoch<9223372036854775807
      AND NOT EXISTS (SELECT 1 FROM spoonbill_browser_replacement r WHERE r.realm=v.realm AND r.namespace=v.cookie_namespace AND r.view_id=v.view_id)"""
    execute(connection, s"""DELETE FROM spoonbill_view_snapshot s USING spoonbill_browser_view v
      WHERE s.realm=? AND s.cookie_namespace=? AND v.realm=s.realm AND v.cookie_namespace=s.cookie_namespace
      AND v.view_id=s.view_id AND $expiredView""", viewCut)
    execute(connection, s"""WITH removed AS (DELETE FROM spoonbill_browser_view v WHERE v.realm=? AND v.cookie_namespace=?
      AND $expiredView RETURNING realm,cookie_namespace,epoch)
      UPDATE spoonbill_browser_retention m SET view_floor=GREATEST(m.view_floor,n.floor)
      FROM (SELECT realm,cookie_namespace,MAX(epoch)+1 AS floor FROM removed GROUP BY realm,cookie_namespace) n
      WHERE m.realm=n.realm AND m.namespace=n.cookie_namespace""", viewCut)
    execute(connection, s"""WITH removed AS (DELETE FROM spoonbill_browser_slot b WHERE b.realm=? AND b.cookie_namespace=?
      AND b.current_session_id IS NULL AND b.last_seen<=? AND b.generation<9223372036854775807
      AND NOT EXISTS (SELECT 1 FROM spoonbill_browser_view v WHERE v.realm=b.realm AND v.cookie_namespace=b.cookie_namespace AND v.binding_hash=b.binding_hash)
      AND NOT EXISTS (SELECT 1 FROM spoonbill_browser_session s WHERE s.realm=b.realm AND s.cookie_namespace=b.cookie_namespace AND s.binding_hash=b.binding_hash)
      AND NOT EXISTS (SELECT 1 FROM spoonbill_auth_ceremony c WHERE c.realm=b.realm AND c.namespace=b.cookie_namespace AND c.binding_hash=b.binding_hash)
      AND NOT EXISTS (SELECT 1 FROM spoonbill_browser_replacement r WHERE r.realm=b.realm AND r.namespace=b.cookie_namespace AND r.binding_hash=b.binding_hash)
      RETURNING realm,cookie_namespace,generation)
      UPDATE spoonbill_browser_retention m SET binding_floor=GREATEST(m.binding_floor,n.floor)
      FROM (SELECT realm,cookie_namespace,MAX(generation)+1 AS floor FROM removed GROUP BY realm,cookie_namespace) n
      WHERE m.realm=n.realm AND m.namespace=n.cookie_namespace""", bindingCut)
  }
}

private[jdbc] object JdbcBrowserRetention {
  def initialize(connection: Connection): Unit = schema.foreach(text => Using.resource(connection.createStatement())(_.executeUpdate(text)))

  private val schema = Vector(
    """CREATE TABLE IF NOT EXISTS spoonbill_browser_work (
      realm VARCHAR(96) NOT NULL, namespace VARCHAR(96) NOT NULL,
      kind VARCHAR(16) NOT NULL CHECK(kind IN ('retention','capacity')),
      epoch_second BIGINT NOT NULL, nano INTEGER NOT NULL CHECK(nano>=0 AND nano<1000000000),
      PRIMARY KEY(realm,namespace,kind))""",
    """CREATE TABLE IF NOT EXISTS spoonbill_browser_retention (
      realm VARCHAR(96) NOT NULL, namespace VARCHAR(96) NOT NULL, id_prefix BIGINT NOT NULL, credential_key BYTEA NOT NULL,
      id_counter BIGINT NOT NULL DEFAULT 0 CHECK(id_counter>=0 AND id_counter<=4611686018427387903),
      binding_floor BIGINT NOT NULL DEFAULT 0 CHECK(binding_floor>=0),
      view_floor BIGINT NOT NULL DEFAULT 1 CHECK(view_floor>0), PRIMARY KEY(realm,namespace))""",
    """ALTER TABLE spoonbill_browser_slot ADD COLUMN IF NOT EXISTS last_seen
      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP""",
    """ALTER TABLE spoonbill_browser_view ADD COLUMN IF NOT EXISTS last_seen
      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP"""
  )
}
