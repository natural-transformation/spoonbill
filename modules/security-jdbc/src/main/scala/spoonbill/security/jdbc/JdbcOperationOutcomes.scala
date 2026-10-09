package spoonbill.security.jdbc

import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}
import java.time.Instant
import java.util.UUID
import scala.util.Using
import scala.util.control.NonFatal
import spoonbill.security.*
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.store.{GrantDefinition, InvocationStatus}
import spoonbill.security.transaction.*

/** Retained PostgreSQL outcome records. No independent transaction or connection
  * is opened here. Call readForDecision before host locks or writes, retaining that
  * same READ COMMITTED transaction through mutation and recordOutcome. Each
  * transaction handles one operation; all writers must follow this lock order.
  * initialize is an explicit schema-management operation.
  *
  * The happy issuer-bound path is three frontend SQL executions: ordered locks,
  * a fresh post-lock identity read, and a guarded outcome write. The lock query
  * intentionally cannot also read rows: its initial statement snapshot could
  * predate a commit that it waits for. Fewer frontend calls do not imply that
  * advisory locks or the retained-capacity count perform no database work.
  */
final class JdbcOperationOutcomes(maxOutcomesPerSubject: Int = 2048)
    extends DurableOperationStore[Direct, Connection] {
  require(maxOutcomesPerSubject > 0, "Outcome capacity must be positive")

  def initialize(connection: Connection): Unit = safely {
    schema.foreach(sql => Using.resource(connection.createStatement())(_.executeUpdate(sql)))
  }

  def readForDecision(connection: Connection, invocation: OperationInvocation): Direct[Option[StoredOperation]] = safely {
    requireTransaction(connection)
    statement(connection, lockSql) { query =>
      val binding = invocation.binding
      query.setString(1, s"spoonbill.outcome.subject:${binding.realmId}:${binding.subjectId}")
      query.setString(2, s"spoonbill.outcome.grant:${invocation.grantId}")
      query.setString(3, s"spoonbill.outcome.invocation:${invocation.invocationId}")
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) refuse(OperationError.StorageFailure)
        if (!rows.getBoolean(1)) refuse(OperationError.IsolationRequired)
        if (rows.getInt(2) != 1) refuse(OperationError.StorageFailure)
      }
    }
    // Separate statement, deliberately fresh after every advisory-lock wait.
    statement(connection, "SELECT * FROM spoonbill_operation_outcome WHERE invocation_id=? OR grant_id=?") { query =>
      query.setObject(1, invocation.invocationId.toUuid)
      query.setObject(2, invocation.grantId.toUuid)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) None
        else {
          val record = read(rows)
          if (rows.next() || record.operation.invocation != invocation)
            refuse(OperationError.InvocationConflict)
          Some(record)
        }
      }
    }
  }

  def insertPrepared(connection: Connection, operation: PreparedOperation): Direct[Unit] =
    write(connection, operation, InvocationStatus.InProgress)

  def recordOutcome(connection: Connection, operation: PreparedOperation, status: InvocationStatus): Direct[Unit] = {
    if (status != InvocationStatus.Committed && status != InvocationStatus.NotCommitted)
      refuse(OperationError.InvalidDefinition)
    write(connection, operation, status)
  }

  private def write(connection: Connection, operation: PreparedOperation, status: InvocationStatus): Unit = safely {
    requireTransaction(connection)
    OperationProtocol.validateDefinition(operation)
    statement(connection, writeSql) { query =>
      val invocation = operation.invocation
      val binding = invocation.binding
      query.setObject(1, invocation.invocationId.toUuid)
      query.setObject(2, invocation.grantId.toUuid)
      query.setObject(3, binding.subjectId.toUuid)
      query.setObject(4, binding.realmId.toUuid)
      query.setObject(5, binding.sessionId.toUuid)
      query.setLong(6, binding.securityGeneration.toLong)
      query.setObject(7, binding.purpose.toUuid)
      query.setObject(8, binding.resourceScope.toUuid)
      query.setBytes(9, invocation.requestDigest.bytes)
      query.setLong(10, operation.definition.expiresAt.getEpochSecond)
      query.setInt(11, operation.definition.expiresAt.getNano)
      query.setString(12, operation.definition.policy.toString)
      query.setString(13, operation.kind.toString)
      query.setString(14, status.toString)
      query.setObject(15, invocation.invocationId.toUuid)
      query.setObject(16, binding.realmId.toUuid)
      query.setObject(17, binding.subjectId.toUuid)
      query.setInt(18, maxOutcomesPerSubject)
      if (query.executeUpdate() != 1) refuse(OperationError.CapacityOrConflict)
    }
  }

  private def read(rows: ResultSet): StoredOperation = {
    def uuid(column: String): UUID = rows.getObject(column, classOf[UUID])
    val binding = OperationBinding(
      SubjectId.fromUuid(uuid("subject_id")), RealmId.fromUuid(uuid("realm_id")),
      AuthSessionId.fromUuid(uuid("session_id")),
      SecurityGeneration.fromLong(rows.getLong("security_generation"))
        .fold(_ => refuse(OperationError.StorageFailure), identity),
      OperationPurpose.fromUuid(uuid("purpose")), ResourceScope.fromUuid(uuid("resource_scope")))
    val grantId = OperationAuthorizationId.fromUuid(uuid("grant_id"))
    val definition = GrantDefinition(grantId, binding,
      Instant.ofEpochSecond(rows.getLong("expires_at_seconds"), rows.getInt("expires_at_nanos").toLong),
      ReservationPolicy.valueOf(rows.getString("policy")))
    val invocation = OperationInvocation(InvocationId.fromUuid(uuid("invocation_id")), grantId, binding,
      RequestDigest.fromBytes(rows.getBytes("request_digest")).fold(_ => refuse(OperationError.StorageFailure), identity))
    StoredOperation(PreparedOperation(definition, invocation, PreparationKind.valueOf(rows.getString("kind"))),
      InvocationStatus.valueOf(rows.getString("status")))
  }

  private def requireTransaction(connection: Connection): Unit =
    if (connection.getAutoCommit) refuse(OperationError.TransactionRequired)

  private def safely[A](operation: => A): A = try operation catch {
    case error: OperationProtocolException => throw error
    case error: SQLException if error.getSQLState == "23505" => refuse(OperationError.InvocationConflict)
    case NonFatal(_) => refuse(OperationError.StorageFailure)
  }

  private def refuse(error: OperationError): Nothing = throw new OperationProtocolException(error)
  private def statement[A](connection: Connection, sql: String)(body: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(sql))(body)

  private val lockSql = """WITH isolation AS MATERIALIZED (
      SELECT current_setting('transaction_isolation')='read committed' AS allowed
    ), subject_fence AS MATERIALIZED (
      SELECT pg_advisory_xact_lock(hashtextextended(?,0)) FROM isolation WHERE allowed
    ), grant_fence AS MATERIALIZED (
      SELECT pg_advisory_xact_lock(hashtextextended(?,0)) FROM subject_fence
    ), invocation_fence AS MATERIALIZED (
      SELECT pg_advisory_xact_lock(hashtextextended(?,0)) FROM grant_fence
    )
    SELECT allowed, (SELECT count(*) FROM invocation_fence) FROM isolation"""

  private val writeSql = """INSERT INTO spoonbill_operation_outcome
    (invocation_id,grant_id,subject_id,realm_id,session_id,security_generation,purpose,resource_scope,
     request_digest,expires_at_seconds,expires_at_nanos,policy,kind,status)
    SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?
    WHERE current_setting('transaction_isolation')='read committed'
      AND (EXISTS (SELECT 1 FROM spoonbill_operation_outcome WHERE invocation_id=?)
        OR (SELECT count(*) FROM spoonbill_operation_outcome WHERE realm_id=? AND subject_id=?) < ?)
    ON CONFLICT (invocation_id) DO UPDATE SET status=EXCLUDED.status
    WHERE (spoonbill_operation_outcome.grant_id,spoonbill_operation_outcome.subject_id,
      spoonbill_operation_outcome.realm_id,spoonbill_operation_outcome.session_id,
      spoonbill_operation_outcome.security_generation,spoonbill_operation_outcome.purpose,
      spoonbill_operation_outcome.resource_scope,spoonbill_operation_outcome.request_digest,
      spoonbill_operation_outcome.expires_at_seconds,spoonbill_operation_outcome.expires_at_nanos,
      spoonbill_operation_outcome.policy,spoonbill_operation_outcome.kind)
      = (EXCLUDED.grant_id,EXCLUDED.subject_id,EXCLUDED.realm_id,EXCLUDED.session_id,
        EXCLUDED.security_generation,EXCLUDED.purpose,EXCLUDED.resource_scope,EXCLUDED.request_digest,
        EXCLUDED.expires_at_seconds,EXCLUDED.expires_at_nanos,EXCLUDED.policy,EXCLUDED.kind)
      AND (spoonbill_operation_outcome.status=EXCLUDED.status
        OR (spoonbill_operation_outcome.status IN ('InProgress','Unknown')
          AND EXCLUDED.status IN ('Committed','NotCommitted')))"""

  private val schema = Vector(
    """CREATE TABLE IF NOT EXISTS spoonbill_operation_outcome (
      invocation_id UUID PRIMARY KEY, grant_id UUID NOT NULL UNIQUE,
      subject_id UUID NOT NULL, realm_id UUID NOT NULL, session_id UUID NOT NULL,
      security_generation BIGINT NOT NULL CHECK(security_generation>=0),
      purpose UUID NOT NULL, resource_scope UUID NOT NULL,
      request_digest BYTEA NOT NULL CHECK(octet_length(request_digest)=32),
      expires_at_seconds BIGINT NOT NULL,
      expires_at_nanos INTEGER NOT NULL CHECK(expires_at_nanos>=0 AND expires_at_nanos<1000000000),
      policy TEXT NOT NULL CHECK(policy='ConsumeOnAttempt'),
      kind TEXT NOT NULL CHECK(kind IN ('Verified','Conditional')),
      status TEXT NOT NULL CHECK(status IN ('InProgress','Unknown','Committed','NotCommitted')))""",
    "CREATE INDEX IF NOT EXISTS spoonbill_operation_outcome_subject ON spoonbill_operation_outcome(realm_id,subject_id)"
  )
}
