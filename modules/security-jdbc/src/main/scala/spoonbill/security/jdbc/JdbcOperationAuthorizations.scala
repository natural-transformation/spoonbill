package spoonbill.security.jdbc

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp, Types}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using
import scala.util.control.NonFatal
import spoonbill.security.*
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.store.{GrantDefinition, InvocationStatus}

/** Blocking PostgreSQL operation ledger. Schedule on a host blocking executor.
  * Issuance is a trusted provider port, never a browser endpoint. Every operation
  * takes locks in order: realm/subject advisory, global invocation advisory,
  * grant/invocation rows. executeIn must precede ALL host row locks. Host callbacks
  * may only use the supplied connection, must validate current host authority
  * after their locks, and must not commit, close, await, or perform external IO.
  *
  * Reservation commits before a permit is returned. Host mutation and the
  * Committed marker share a second transaction. Failure leaves a durable
  * reservation; fenced reconciliation, never elapsed time, settles it. All
  * records are retained and capacity fails closed: there is no TTL deletion.
  */
final class JdbcOperationAuthorizations(
  dataSource: DataSource,
  clock: () => Instant,
  maxGrantsPerSubject: Int = 1024,
  maxInvocationsPerSubject: Int = 2048
) {
  require(maxGrantsPerSubject > 0 && maxInvocationsPerSubject > 0, "Operation capacities must be positive")
  private case class GrantRow(authorization: OperationAuthorization, conditional: Boolean)

  def initialize(connection: Connection): Unit = safely {
    schema.foreach(sql => Using.resource(connection.createStatement())(_.executeUpdate(sql)))
  }

  /** The provider has established the policy's evidence before calling issue. */
  def issue(definition: GrantDefinition): Either[JdbcOperationError, Unit] = transaction { connection =>
    subjectLock(connection, definition.binding.realmId, definition.binding.subjectId)
    ensureGrant(connection, definition, conditional = false)
    ()
  }

  def reserve(invocation: OperationInvocation): Either[JdbcOperationError, OperationPreparation] = transaction { connection =>
    locks(connection, invocation)
    val grant = requiredGrant(connection, invocation)
    if (grant.conditional) refuse(JdbcOperationError.DefinitionConflict)
    reserveLocked(connection, invocation, grant)
  }

  /** Trusted provider shortcut when policy evidence and exact intent are already
    * established. Issuance and reservation share one transaction; execution still
    * requires executeIn in a separate host transaction. Unknown commit outcomes
    * never return a permit, and retries return durable status without replay.
    */
  def issueAndReserve(definition: GrantDefinition, invocation: OperationInvocation)
    : Either[JdbcOperationError, OperationPreparation] = transaction { connection =>
    if (definition.id != invocation.grantId || definition.binding != invocation.binding)
      refuse(JdbcOperationError.DefinitionConflict)
    prepareExactAttempt(connection, definition, invocation, conditional = false)
  }

  /** Provisional exact-operation admission for a host whose proof replay check
    * and consumption occur in the protected transaction. This never creates an
    * Available grant. executeIn's host callback must establish/consume that
    * evidence atomically with its mutation. Failure ALWAYS burns the attempt.
    */
  def prepareConditionalAttempt(definition: GrantDefinition, invocation: OperationInvocation)
    : Either[JdbcOperationError, OperationPreparation] = transaction { connection =>
    checkConditionalDefinition(definition, invocation)
    prepareExactAttempt(connection, definition, invocation, conditional = true)
  }

  private def prepareExactAttempt(connection: Connection, definition: GrantDefinition,
    invocation: OperationInvocation, conditional: Boolean): OperationPreparation = {
    locks(connection, invocation)
    val existing = grantRow(connection, invocation.grantId)
    existing match {
      case Some(row) =>
        checkDefinition(row, definition, conditional)
        reserveLocked(connection, invocation, row)
      case None =>
        if (invocationRow(connection, invocation.invocationId).nonEmpty) refuse(JdbcOperationError.InvocationConflict)
        checkCapacity(connection, invocation.binding, grants = true)
        checkCapacity(connection, invocation.binding, grants = false)
        val authorization = initial(definition)
        val reserved = authorization.reserve(invocation.binding, invocation.invocationId, clock())
          .fold(error => refuse(mapError(error)), _.authorization)
        insertGrant(connection, GrantRow(reserved, conditional))
        insertInvocation(connection, DurableInvocationRecord(invocation, InvocationStatus.InProgress))
        OperationPreparation.Acquired(new ExecutionPermit(invocation))
    }
  }

  /** Throws on refusal so the host MUST roll back its entire transaction.
    * The supplied Instant is fresh after ledger locks. Hosts must check their
    * own current authority using fresh time after acquiring host locks too.
    * Grant expiry is checked again after the callback before staging commit.
    */
  def executeIn[A](connection: Connection, permit: ExecutionPermit)(
    verifyAndMutate: (Connection, Instant) => Either[JdbcOperationError, A]
  ): A = safely {
    requireTransaction(connection)
    if (!permit.claim()) refuse(JdbcOperationError.PermitUsed)
    val invocation = permit.invocation
    locks(connection, invocation)
    val grant = requiredGrant(connection, invocation).authorization
    val record = requiredInvocation(connection, invocation)
    if (record.status != InvocationStatus.InProgress) refuse(JdbcOperationError.NotReserved)
    grant.state match {
      case OperationAuthorizationState.Reserved(owner, None) if owner == invocation.invocationId => ()
      case OperationAuthorizationState.Reserved(_, Some(reason)) => refuse(invalidationError(reason))
      case _ => refuse(JdbcOperationError.NotReserved)
    }
    val now = clock()
    if (!now.isBefore(grant.expiresAt)) refuse(JdbcOperationError.Expired)
    val value = verifyAndMutate(connection, now).fold(refuse, identity)
    if (!clock().isBefore(grant.expiresAt)) refuse(JdbcOperationError.Expired)
    // A host may invalidate sibling grants as part of its mutation. Re-read so
    // that late completion preserves that invalidation and records only status.
    val current = requiredGrant(connection, invocation).authorization
    updateGrant(connection, current.commit(invocation.invocationId).fold(error => refuse(mapError(error)), identity))
    updateInvocation(connection, invocation.invocationId, InvocationStatus.Committed)
    value
  }

  def readStatus(invocation: OperationInvocation): Either[JdbcOperationError, DurableInvocationRecord] = transaction { connection =>
    locks(connection, invocation)
    requiredGrant(connection, invocation)
    requiredInvocation(connection, invocation)
  }

  /** Locking waits out an executing writer, or fences a permit not yet started.
    * Since the mutation can only commit with this ledger's marker, an unresolved
    * row under these locks can be authoritatively settled as NotCommitted.
    * This protocol does not apply to external or independently committed effects.
    */
  def reconcile(invocation: OperationInvocation): Either[JdbcOperationError, DurableInvocationRecord] = transaction { connection =>
    locks(connection, invocation)
    val grant = requiredGrant(connection, invocation).authorization
    settleLocked(connection, invocation, grant)
  }

  /** Reconcile using the original trusted definition when conditional preparation
    * may have rolled back BOTH rows. The same locks exclude a late original
    * prepare; burned grant + terminal invocation are retained even in this case.
    */
  def reconcileConditionalAttempt(definition: GrantDefinition, invocation: OperationInvocation)
    : Either[JdbcOperationError, DurableInvocationRecord] = transaction { connection =>
    checkConditionalDefinition(definition, invocation)
    locks(connection, invocation)
    grantRow(connection, invocation.grantId) match {
      case Some(row) =>
        checkDefinition(row, definition, conditional = true)
        settleLocked(connection, invocation, row.authorization)
      case None =>
        if (invocationRow(connection, invocation.invocationId).nonEmpty) refuse(JdbcOperationError.InvocationConflict)
        checkCapacity(connection, invocation.binding, grants = true)
        checkCapacity(connection, invocation.binding, grants = false)
        val burned = initial(definition).copy(state = OperationAuthorizationState.Invalidated(GrantInvalidation.Burned))
        insertGrant(connection, GrantRow(burned, conditional = true))
        val record = DurableInvocationRecord(invocation, InvocationStatus.NotCommitted)
        insertInvocation(connection, record)
        record
    }
  }

  private def settleLocked(connection: Connection, invocation: OperationInvocation,
    grant: OperationAuthorization): DurableInvocationRecord = {
    invocationRow(connection, invocation.invocationId) match {
      case Some(record) =>
        checkInvocation(record, invocation)
        record.status match {
          case InvocationStatus.Committed | InvocationStatus.NotCommitted => record
          case InvocationStatus.InProgress | InvocationStatus.Unknown =>
            val settled = grant.state match {
              case _: OperationAuthorizationState.Reserved => grant.release(invocation.invocationId, clock())
              case _: OperationAuthorizationState.Unknown => grant.reconcileNotCommitted(invocation.invocationId, clock())
              case _ => refuse(JdbcOperationError.NotReserved)
            }
            updateGrant(connection, settled.fold(error => refuse(mapError(error)), identity))
            updateInvocation(connection, invocation.invocationId, InvocationStatus.NotCommitted)
            record.copy(status = InvocationStatus.NotCommitted)
        }
      case None =>
        // Also fence an uncertain reservation transaction that actually rolled
        // back. The global invocation lock excludes its original inserter.
        grant.state match {
          case OperationAuthorizationState.Available => ()
          case OperationAuthorizationState.Reserved(owner, _) if owner != invocation.invocationId =>
            refuse(JdbcOperationError.CompetingInvocation)
          case OperationAuthorizationState.Unknown(owner, _) if owner != invocation.invocationId =>
            refuse(JdbcOperationError.CompetingInvocation)
          case OperationAuthorizationState.Committed(owner) if owner != invocation.invocationId =>
            refuse(JdbcOperationError.CompetingInvocation)
          case OperationAuthorizationState.Invalidated(reason) => refuse(invalidationError(reason))
          case _ => refuse(JdbcOperationError.StorageFailure)
        }
        checkCapacity(connection, invocation.binding, grants = false)
        val record = DurableInvocationRecord(invocation, InvocationStatus.NotCommitted)
        insertInvocation(connection, record)
        record
    }
  }

  /** Join the host mutation transaction. Calls for another subject are forbidden
    * inside executeIn: the host must retain the operation's subject lock order.
    * Unresolved invocation identities and all tombstones remain intact.
    */
  def invalidateSubject(connection: Connection, realm: RealmId, subject: SubjectId,
    supersededGeneration: SecurityGeneration): Unit = safely {
    requireTransaction(connection)
    subjectLock(connection, realm, subject)
    // The subject lock serializes ledger changes. Match revoke without fetching
    // retained rows or issuing one update per grant; keep unresolved owners.
    statement(connection, """UPDATE spoonbill_operation_grant
      SET state=CASE WHEN state='Available' THEN 'Invalidated' ELSE state END,
          invalidation='Revoked'
      WHERE realm_id=? AND subject_id=? AND security_generation<=?
        AND state IN ('Available','Reserved','Unknown')
        AND invalidation IS DISTINCT FROM 'Revoked'""") { query =>
      query.setObject(1, realm.toUuid); query.setObject(2, subject.toUuid); query.setLong(3, supersededGeneration.toLong)
      query.executeUpdate(); ()
    }
  }

  private def reserveLocked(connection: Connection, invocation: OperationInvocation, row: GrantRow): OperationPreparation = {
    if (row.authorization.binding != invocation.binding) refuse(JdbcOperationError.BindingMismatch)
    invocationRow(connection, invocation.invocationId) match {
      case Some(record) => checkInvocation(record, invocation); OperationPreparation.Known(record)
      case None =>
        val reserved = row.authorization.reserve(invocation.binding, invocation.invocationId, clock())
          .fold(error => refuse(mapError(error)), identity)
        if (reserved.decision != ReservationDecision.Acquired) refuse(JdbcOperationError.InvocationConflict)
        checkCapacity(connection, invocation.binding, grants = false)
        updateGrant(connection, reserved.authorization)
        insertInvocation(connection, DurableInvocationRecord(invocation, InvocationStatus.InProgress))
        OperationPreparation.Acquired(new ExecutionPermit(invocation))
    }
  }

  private def ensureGrant(connection: Connection, definition: GrantDefinition, conditional: Boolean): Unit =
    grantRow(connection, definition.id) match {
      case Some(row) => checkDefinition(row, definition, conditional)
      case None =>
        checkCapacity(connection, definition.binding, grants = true)
        insertGrant(connection, GrantRow(initial(definition), conditional))
    }

  private def initial(definition: GrantDefinition): OperationAuthorization =
    OperationAuthorization(definition.id, definition.binding, definition.expiresAt.truncatedTo(ChronoUnit.MICROS),
      definition.policy, OperationAuthorizationState.Available)

  private def checkDefinition(row: GrantRow, definition: GrantDefinition, conditional: Boolean): Unit =
    if (row.conditional != conditional || row.authorization.copy(state = OperationAuthorizationState.Available) != initial(definition))
      refuse(JdbcOperationError.DefinitionConflict)

  private def checkConditionalDefinition(definition: GrantDefinition, invocation: OperationInvocation): Unit =
    if (definition.policy != ReservationPolicy.ConsumeOnAttempt || definition.id != invocation.grantId ||
        definition.binding != invocation.binding) refuse(JdbcOperationError.DefinitionConflict)

  private def requiredGrant(connection: Connection, invocation: OperationInvocation): GrantRow = {
    val row = grantRow(connection, invocation.grantId).getOrElse(refuse(JdbcOperationError.NotFound))
    if (row.authorization.binding != invocation.binding) refuse(JdbcOperationError.BindingMismatch)
    row
  }

  private def requiredInvocation(connection: Connection, invocation: OperationInvocation): DurableInvocationRecord = {
    val record = invocationRow(connection, invocation.invocationId).getOrElse(refuse(JdbcOperationError.NotFound))
    checkInvocation(record, invocation)
    record
  }

  private def checkInvocation(record: DurableInvocationRecord, invocation: OperationInvocation): Unit =
    if (record.invocation != invocation) refuse(JdbcOperationError.InvocationConflict)

  private def checkCapacity(connection: Connection, binding: OperationBinding, grants: Boolean): Unit = {
    val table = if (grants) "spoonbill_operation_grant" else "spoonbill_operation_invocation"
    val limit = if (grants) maxGrantsPerSubject else maxInvocationsPerSubject
    val count = statement(connection, s"SELECT count(*) FROM $table WHERE realm_id=? AND subject_id=?") { query =>
      query.setObject(1, binding.realmId.toUuid); query.setObject(2, binding.subjectId.toUuid)
      Using.resource(query.executeQuery()) { rows => rows.next(); rows.getLong(1) }
    }
    if (count >= limit) refuse(JdbcOperationError.CapacityExceeded)
  }

  private def locks(connection: Connection, invocation: OperationInvocation): Unit = {
    subjectLock(connection, invocation.binding.realmId, invocation.binding.subjectId)
    advisory(connection, "spoonbill.operation.invocation:" + invocation.invocationId.toString)
  }

  private def subjectLock(connection: Connection, realm: RealmId, subject: SubjectId): Unit =
    advisory(connection, s"spoonbill.operation.subject:$realm:$subject")

  private def advisory(connection: Connection, key: String): Unit =
    statement(connection, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))") { query =>
      query.setString(1, key); Using.resource(query.executeQuery())(_ => ())
    }

  private def grantRow(connection: Connection, id: OperationAuthorizationId): Option[GrantRow] =
    statement(connection, "SELECT * FROM spoonbill_operation_grant WHERE grant_id=? FOR UPDATE") { query =>
      query.setObject(1, id.toUuid)
      Using.resource(query.executeQuery()) { rows => if (rows.next()) Some(readGrant(rows)) else None }
    }

  private def readGrant(rows: ResultSet): GrantRow = {
    def owner = InvocationId.fromUuid(rows.getObject("owner_invocation", classOf[UUID]))
    val invalidation = Option(rows.getString("invalidation")).map(GrantInvalidation.valueOf)
    val state = rows.getString("state") match {
      case "Available" => OperationAuthorizationState.Available
      case "Reserved" => OperationAuthorizationState.Reserved(owner, invalidation)
      case "Unknown" => OperationAuthorizationState.Unknown(owner, invalidation)
      case "Committed" => OperationAuthorizationState.Committed(owner)
      case "Invalidated" => OperationAuthorizationState.Invalidated(invalidation.getOrElse(refuse(JdbcOperationError.StorageFailure)))
      case _ => refuse(JdbcOperationError.StorageFailure)
    }
    GrantRow(OperationAuthorization(OperationAuthorizationId.fromUuid(rows.getObject("grant_id", classOf[UUID])),
      readBinding(rows), rows.getTimestamp("expires_at").toInstant, ReservationPolicy.valueOf(rows.getString("policy")), state),
      rows.getBoolean("conditional"))
  }

  private def invocationRow(connection: Connection, id: InvocationId): Option[DurableInvocationRecord] =
    statement(connection, "SELECT * FROM spoonbill_operation_invocation WHERE invocation_id=? FOR UPDATE") { query =>
      query.setObject(1, id.toUuid)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) None
        else Some(DurableInvocationRecord(OperationInvocation(id,
          OperationAuthorizationId.fromUuid(rows.getObject("grant_id", classOf[UUID])), readBinding(rows),
          RequestDigest.fromBytes(rows.getBytes("request_digest")).fold(_ => refuse(JdbcOperationError.StorageFailure), identity)),
          InvocationStatus.valueOf(rows.getString("status"))))
      }
    }

  private def readBinding(rows: ResultSet): OperationBinding = OperationBinding(
    SubjectId.fromUuid(rows.getObject("subject_id", classOf[UUID])),
    RealmId.fromUuid(rows.getObject("realm_id", classOf[UUID])),
    AuthSessionId.fromUuid(rows.getObject("session_id", classOf[UUID])),
    SecurityGeneration.fromLong(rows.getLong("security_generation")).fold(_ => refuse(JdbcOperationError.StorageFailure), identity),
    OperationPurpose.fromUuid(rows.getObject("purpose", classOf[UUID])),
    ResourceScope.fromUuid(rows.getObject("resource_scope", classOf[UUID])))

  private def bind(query: PreparedStatement, binding: OperationBinding, start: Int): Unit = {
    query.setObject(start, binding.subjectId.toUuid); query.setObject(start + 1, binding.realmId.toUuid)
    query.setObject(start + 2, binding.sessionId.toUuid); query.setLong(start + 3, binding.securityGeneration.toLong)
    query.setObject(start + 4, binding.purpose.toUuid); query.setObject(start + 5, binding.resourceScope.toUuid)
  }

  private def insertGrant(connection: Connection, row: GrantRow): Unit = {
    val grant = row.authorization
    statement(connection, """INSERT INTO spoonbill_operation_grant
      (grant_id,subject_id,realm_id,session_id,security_generation,purpose,resource_scope,expires_at,policy,conditional,state,owner_invocation,invalidation)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""") { query =>
      query.setObject(1, grant.id.toUuid); bind(query, grant.binding, 2)
      query.setTimestamp(8, Timestamp.from(grant.expiresAt)); query.setString(9, grant.policy.toString)
      query.setBoolean(10, row.conditional)
      bindState(query, grant, 11)
      query.executeUpdate(); ()
    }
  }

  private def bindState(query: PreparedStatement, grant: OperationAuthorization, start: Int): Unit = {
    val (state, owner, invalidation) = grant.state match {
      case OperationAuthorizationState.Available => ("Available", None, None)
      case OperationAuthorizationState.Reserved(id, reason) => ("Reserved", Some(id), reason)
      case OperationAuthorizationState.Unknown(id, reason) => ("Unknown", Some(id), reason)
      case OperationAuthorizationState.Committed(id) => ("Committed", Some(id), None)
      case OperationAuthorizationState.Invalidated(reason) => ("Invalidated", None, Some(reason))
    }
    query.setString(start, state)
    owner.fold(query.setNull(start + 1, Types.OTHER))(id => query.setObject(start + 1, id.toUuid))
    invalidation.fold(query.setNull(start + 2, Types.VARCHAR))(reason => query.setString(start + 2, reason.toString))
  }

  private def updateGrant(connection: Connection, grant: OperationAuthorization): Unit =
    statement(connection, "UPDATE spoonbill_operation_grant SET state=?,owner_invocation=?,invalidation=? WHERE grant_id=?") { query =>
      bindState(query, grant, 1)
      query.setObject(4, grant.id.toUuid); query.executeUpdate(); ()
    }

  private def insertInvocation(connection: Connection, record: DurableInvocationRecord): Unit =
    statement(connection, """INSERT INTO spoonbill_operation_invocation
      (invocation_id,grant_id,subject_id,realm_id,session_id,security_generation,purpose,resource_scope,request_digest,status)
      VALUES (?,?,?,?,?,?,?,?,?,?)""") { query =>
      val request = record.invocation
      query.setObject(1, request.invocationId.toUuid); query.setObject(2, request.grantId.toUuid); bind(query, request.binding, 3)
      query.setBytes(9, request.requestDigest.bytes); query.setString(10, record.status.toString); query.executeUpdate(); ()
    }

  private def updateInvocation(connection: Connection, id: InvocationId, status: InvocationStatus): Unit =
    statement(connection, "UPDATE spoonbill_operation_invocation SET status=? WHERE invocation_id=?") { query =>
      query.setString(1, status.toString); query.setObject(2, id.toUuid); query.executeUpdate(); ()
    }

  private def transaction[A](operation: Connection => A): Either[JdbcOperationError, A] =
    JdbcTransactions.run[JdbcOperationError, A](dataSource, JdbcOperationError.StorageFailure, JdbcOperationError.CommitUnknown) { connection =>
      try Right(operation(connection)) catch {
        case error: JdbcOperationException => Left(error.error)
        case NonFatal(_) => Left(JdbcOperationError.StorageFailure)
      }
    }

  private def requireTransaction(connection: Connection): Unit =
    if (connection.getAutoCommit) refuse(JdbcOperationError.TransactionRequired)

  private def safely[A](operation: => A): A = try operation catch {
    case error: JdbcOperationException => throw error
    case NonFatal(_) => refuse(JdbcOperationError.StorageFailure)
  }
  private def refuse(error: JdbcOperationError): Nothing = throw new JdbcOperationException(error)
  private def statement[A](connection: Connection, sql: String)(run: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(sql))(run)

  private def invalidationError(reason: GrantInvalidation): JdbcOperationError = reason match {
    case GrantInvalidation.Expired => JdbcOperationError.Expired
    case GrantInvalidation.Revoked => JdbcOperationError.Revoked
    case GrantInvalidation.Burned => JdbcOperationError.Burned
  }
  private def mapError(error: OperationAuthorizationError): JdbcOperationError = error match {
    case OperationAuthorizationError.BindingMismatch => JdbcOperationError.BindingMismatch
    case OperationAuthorizationError.Expired => JdbcOperationError.Expired
    case OperationAuthorizationError.Revoked => JdbcOperationError.Revoked
    case OperationAuthorizationError.Burned => JdbcOperationError.Burned
    case OperationAuthorizationError.CompetingInvocation => JdbcOperationError.CompetingInvocation
    case _ => JdbcOperationError.NotReserved
  }

  private val bindingColumns = """subject_id UUID NOT NULL, realm_id UUID NOT NULL, session_id UUID NOT NULL,
    security_generation BIGINT NOT NULL CHECK(security_generation>=0), purpose UUID NOT NULL, resource_scope UUID NOT NULL"""
  private val schema = Vector(
    s"""CREATE TABLE IF NOT EXISTS spoonbill_operation_grant (
      grant_id UUID PRIMARY KEY, $bindingColumns, expires_at TIMESTAMPTZ NOT NULL,
      policy TEXT NOT NULL CHECK(policy IN ('ReleaseAfterDefiniteFailure','ConsumeOnAttempt')),
      conditional BOOLEAN NOT NULL, state TEXT NOT NULL CHECK(state IN ('Available','Reserved','Unknown','Committed','Invalidated')),
      owner_invocation UUID, invalidation TEXT CHECK(invalidation IN ('Expired','Revoked','Burned')),
      CHECK(NOT conditional OR (policy='ConsumeOnAttempt' AND state<>'Available')),
      CHECK((state IN ('Available','Invalidated') AND owner_invocation IS NULL)
        OR (state IN ('Reserved','Unknown','Committed') AND owner_invocation IS NOT NULL)),
      CHECK(state NOT IN ('Available','Committed') OR invalidation IS NULL),
      CHECK(state<>'Invalidated' OR invalidation IS NOT NULL))""",
    s"""CREATE TABLE IF NOT EXISTS spoonbill_operation_invocation (
      invocation_id UUID PRIMARY KEY, grant_id UUID NOT NULL REFERENCES spoonbill_operation_grant(grant_id),
      $bindingColumns, request_digest BYTEA NOT NULL CHECK(octet_length(request_digest)=32),
      status TEXT NOT NULL CHECK(status IN ('InProgress','Unknown','Committed','NotCommitted')))""",
    "CREATE INDEX IF NOT EXISTS spoonbill_operation_grant_subject ON spoonbill_operation_grant(realm_id,subject_id)",
    "CREATE INDEX IF NOT EXISTS spoonbill_operation_invocation_subject ON spoonbill_operation_invocation(realm_id,subject_id)"
  )
}
