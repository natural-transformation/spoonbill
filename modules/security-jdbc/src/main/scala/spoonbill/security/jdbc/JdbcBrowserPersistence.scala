package spoonbill.security.jdbc

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.{Connection, PreparedStatement, Timestamp}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.Using
import scala.util.control.NonFatal
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.security.transaction.*
import spoonbill.server.BrowserSessionToken

/**
 * Generic durable browser authority, with no account model or proof verifier.
 * Host proof consumption, session creation and audit join the same preparation
 * transaction as the browser session, ceremony outcome and encrypted delivery.
 * The original ceremony row is also the admission/recovery fence; no operation
 * execution ledger or reusable execution authority is created by login.
 *
 * Locks are realm admission -> ceremony -> host -> slot -> view. All operations
 * within a realm are serialized by a transaction advisory lock, including
 * capacity checks; callers of transact must acquire host locks only inside its
 * callback. The explicit durable clock uses an independent reserved connection
 * and therefore survives domain rollback and process restart. Set
 * ownership=BrowserOwnership.SharedViews when multiple providers share this scope:
 * each view is fenced independently, clock observations always consult storage,
 * and claim admission rejects delayed acquisitions overtaken by another owner.
 */
final class JdbcBrowserPersistence[F[_], P](
  source: DataSource,
  blockingContext: ExecutionContext,
  val realm: String,
  val namespace: String,
  clock: () => Instant,
  host: JdbcHostSessions[P],
  vault: JdbcCredentialVault,
  ownership: BrowserOwnership,
  limits: BrowserPersistenceLimits = BrowserPersistenceLimits(),
  existingRunner: Option[TransactionExecutor[F, Direct, Connection]] = None,
  clockSource: Option[DataSource] = None,
  newId: () => UUID = () => UUID.randomUUID()
)(using F: Effect[F])
    extends TransactionalBrowserPersistence[F, Direct, P] {
  final class Transaction private[JdbcBrowserPersistence] (raw: Connection) {
    private val thread = Thread.currentThread()
    private var active = true
    def connection: Connection = {
      if (!active || Thread.currentThread() != thread) reject(OperationError.ScopeClosed)
      raw
    }
    def now: Instant                                  = { connection; JdbcBrowserPersistence.this.now() }
    private[JdbcBrowserPersistence] def close(): Unit = active = false
  }
  type Tx = Transaction

  private def withHostTransaction[A](connection: Connection)(body: Tx => A): A = {
    val tx = new Transaction(connection)
    try body(tx)
    finally tx.close()
  }
  require(realm.matches("[A-Za-z0-9_.-]{1,96}") && namespace.matches("[A-Za-z0-9_.-]{1,96}"))
  def scope: BrowserProviderScope = BrowserProviderScope(realm, namespace)
  def capabilities: BrowserProviderCapabilities =
    BrowserProviderCapabilities(
      atomicHostPreparation = true,
      durable = true,
      ownership = ownership
    )
  private val issuer = new BrowserCapabilityScope[P]
  private val runner = existingRunner.getOrElse(new JdbcTransactionExecutor[F](source, blockingContext))
  private val durableClock = new JdbcBrowserClock(
    clockSource.getOrElse(source),
    realm,
    namespace,
    clock,
    exclusiveClockWriter = ownership == BrowserOwnership.ExclusiveScope
  )
  private val lifecycle        = new Object
  private var stopped          = false
  private var activeOperations = 0
  private var cleanupPending   = 0
  private var closeWaiters     = List.empty[Effect.Promise[Unit]]
  private val maintaining      = new AtomicBoolean()
  private case class OwnedView(binding: Digest256, owner: UUID, epoch: Long)
  private var ownedViews = Map.empty[(String, UUID), OwnedView]
  private val closing    = new AtomicBoolean()
  private val closed     = Promise[Unit]()

  def accepts(receipt: CommittedAuthentication): Boolean = issuer.owns(receipt)
  private def now(): Instant                             = durableClock.now()
  private def reject(error: OperationError = OperationError.HostDenied): Nothing = throw new OperationProtocolException(
    error
  )
  private def digest(value: String): Digest256 =
    Digest256.fromBytes(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8))).toOption.get
  private def key(value: String): Vector[Byte]    = digest(value).bytes.toVector
  private def validSecret(value: String): Boolean = value.matches("[A-Za-z0-9_-]{43}")
  private def sql[A](connection: Connection, text: String)(body: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(text))(body)
  private def scope(query: PreparedStatement): Unit = { query.setString(1, realm); query.setString(2, namespace) }
  private def realmLock(connection: Connection): Unit =
    sql(connection, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))") { query =>
      query.setString(1, s"spoonbill-auth:${realm.length}:$realm$namespace")
      Using.resource(query.executeQuery())(_.next()); ()
    }
  private def browserError(error: JdbcAuthError): OperationError = error match {
    case JdbcAuthError.Expired                                                  => OperationError.Expired
    case JdbcAuthError.NotFound                                                 => OperationError.NotPrepared
    case JdbcAuthError.StorageFailure                                           => OperationError.StorageFailure
    case JdbcAuthError.ViewCapacityExceeded | JdbcAuthError.GenerationExhausted => OperationError.CapacityExceeded
    case _                                                                      => OperationError.HostDenied
  }
  private def failure(value: TransactionFailure): BrowserFailure = value match {
    case TransactionFailure.Rejected(OperationError.Expired)                                   => BrowserFailure.Expired
    case TransactionFailure.Rejected(OperationError.NotPrepared)                               => BrowserFailure.Missing
    case TransactionFailure.Rejected(OperationError.PermitUsed | OperationError.EvidenceUsed)  => BrowserFailure.Used
    case TransactionFailure.Rejected(OperationError.CapacityExceeded)                          => BrowserFailure.Capacity
    case TransactionFailure.Rejected(OperationError.ScopeClosed)                               => BrowserFailure.Closed
    case TransactionFailure.Rejected(OperationError.HostDenied | OperationError.ForeignPermit) => BrowserFailure.Denied
    case other                                                                                 => BrowserFailure.Settlement(other)
  }
  private def pureRequired[A](value: Either[BrowserCeremonyError, A]): A = value.fold(
    {
      case BrowserCeremonyError.Expired => reject(OperationError.Expired)
      case BrowserCeremonyError.Used    => reject(OperationError.PermitUsed)
      case BrowserCeremonyError.Denied  => reject()
    },
    identity
  )

  private def finish(cleanup: Boolean): Unit = {
    val waiters = lifecycle.synchronized {
      if (cleanup) cleanupPending -= 1 else activeOperations -= 1
      if (stopped && activeOperations == 0 && cleanupPending == 0) {
        val result = closeWaiters; closeWaiters = Nil; result
      } else Nil
    }
    waiters.foreach(_(Right(())))
  }
  private def owned[A](
    cleanup: Boolean = false
  )(body: => F[Either[TransactionFailure, A]]): F[Either[TransactionFailure, A]] =
    F.uncancelable {
      F.flatMap(F.delay(lifecycle.synchronized {
        if (stopped) Some(OperationError.ScopeClosed)
        else if ((if (cleanup) cleanupPending else activeOperations) >= limits.pendingJobs)
          Some(OperationError.CapacityExceeded)
        else { if (cleanup) cleanupPending += 1 else activeOperations += 1; None }
      })) {
        case Some(error) => F.pure(Left(TransactionFailure.Rejected(error)))
        case None =>
          val released        = new AtomicBoolean()
          def release(): Unit = if (released.compareAndSet(false, true)) finish(cleanup)
          F.recoverF(F.map(F.delayAsync(body)) { result =>
            release(); result
          }) { case NonFatal(error) =>
            release(); F.fail(error)
          }
      }
    }
  private def transaction[A](body: Connection => A): F[Either[TransactionFailure, A]] = {
    val evaluated = new AtomicBoolean()
    runner.transact { connection =>
      if (!evaluated.compareAndSet(false, true)) reject(OperationError.PermitUsed)
      if (connection.getAutoCommit) reject(OperationError.TransactionRequired)
      try { realmLock(connection); body(connection) }
      catch { case error: JdbcAuthException => reject(browserError(error.error)) }
    }
  }
  private def run[A](body: Connection => A): F[Either[BrowserFailure, A]] =
    F.map(owned()(transaction(body)))(_.left.map(failure))

  private val authorization = new HostSessionAuthorization[HostPrincipal[P]] {
    def authorize(connection: Connection, sessionId: UUID, time: Instant): Option[HostPrincipal[P]] =
      host.resolve(connection, sessionId, time).filter(value => time.isBefore(value.expiresAt))
  }
  private val hooks = new HostSessionHooks {
    def isCurrent(connection: Connection, sessionId: UUID, time: Instant): Boolean =
      authorization.authorize(connection, sessionId, time).nonEmpty
    def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, time: Instant): Unit = {
      host.acknowledge(connection, completionId, sessionId, time)
      sql(
        connection,
        """DELETE FROM spoonbill_auth_material m USING spoonbill_browser_completion c,
        spoonbill_browser_session s WHERE m.realm=? AND m.namespace=? AND c.realm=m.realm
        AND c.cookie_namespace=m.namespace AND c.completion_id=m.attempt AND s.realm=c.realm
        AND s.cookie_namespace=c.cookie_namespace AND s.token_hash=c.token_hash AND c.host_completion_id=? AND s.host_session_id=?"""
      ) { query =>
        scope(query); query.setObject(3, completionId); query.setObject(4, sessionId); query.executeUpdate(); ()
      }
    }
  }
  private val browser =
    new JdbcBrowserSessions(source, realm, namespace, () => now(), hooks, limits.activeViewsPerBinding)

  /** Explicit deployment migration; never run implicitly from a request. */
  def initialize(connection: Connection): Unit = {
    durableClock.initialize(connection)
    browser.initialize(connection)
    schema.foreach(text => Using.resource(connection.createStatement())(_.executeUpdate(text)))
  }

  private def count(connection: Connection, table: String, column: String = "namespace"): Long =
    sql(connection, s"SELECT count(*) FROM $table WHERE realm=? AND $column=?") { query =>
      scope(query);
      Using.resource(query.executeQuery()) { rows =>
        rows.next(); rows.getLong(1)
      }
    }

  private def captureIn(
    connection: Connection,
    binding: String,
    token: Option[String],
    activate: Boolean,
    generation: Option[Long] = None,
    fence: Option[JdbcViewFence] = None
  ): BrowserIdentity[P] = {
    if (!validSecret(binding) || token.exists(value => !validSecret(value))) reject()
    val bindingHash = digest(binding)
    if (activate) token.foreach(value => browser.activateIn(connection, digest(value), bindingHash))
    browser.authorizeIn(
      connection,
      bindingHash,
      token.map(digest),
      authorization,
      generation,
      fence,
      allowPending = !activate
    ) match {
      case JdbcBrowserAuthorization.Anonymous(current) => issuer.identity(binding, token, current, None)
      case JdbcBrowserAuthorization.Authenticated(session, principal) =>
        issuer.identity(binding, token, session.generation, Some(principal), Some(session.hostSessionId))
    }
  }
  private def current(connection: Connection, view: BrowserView[P]): BrowserIdentity[P] = {
    if (!issuer.owns(view)) reject(OperationError.ForeignPermit)
    val captured = view.identity
    val fresh = captureIn(
      connection,
      captured.binding,
      captured.token,
      false,
      Some(captured.generation),
      Some(JdbcViewFence(BrowserView.key(view.qsid), view.ownerId, view.epoch))
    )
    if (!captured.sameAuthority(fresh)) reject()
    fresh
  }

  def capture(
    binding: String,
    token: Option[String],
    activate: Boolean
  ): F[Either[BrowserFailure, BrowserIdentity[P]]] = run { connection =>
    if (!validSecret(binding) || token.exists(value => !validSecret(value))) reject()
    if (token.isEmpty) {
      val exists = sql(
        connection,
        "SELECT 1 FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace=? AND binding_hash=?"
      ) { query =>
        scope(query); query.setBytes(3, digest(binding).bytes); Using.resource(query.executeQuery())(_.next())
      }
      if (!exists) {
        if (count(connection, "spoonbill_browser_slot", "cookie_namespace") >= limits.retainedBindings)
          reject(OperationError.CapacityExceeded)
        sql(
          connection,
          "INSERT INTO spoonbill_browser_slot(realm,cookie_namespace,binding_hash,generation) VALUES (?,?,?,0)"
        ) { query =>
          scope(query); query.setBytes(3, digest(binding).bytes); query.executeUpdate()
        }
      }
    }
    captureIn(connection, binding, token, activate)
  }

  private case class ClaimState(identity: BrowserIdentity[P], id: String, previous: Option[(Vector[Byte], Long, Option[UUID])])

  private def claimState(
    connection: Connection,
    identity: BrowserIdentity[P],
    qsid: Qsid,
    existingOnly: Boolean
  ): ClaimState = {
    if (!issuer.owns(identity)) reject(OperationError.ForeignPermit)
    val fresh = captureIn(connection, identity.binding, identity.token, false, Some(identity.generation))
    if (!identity.sameAuthority(fresh)) reject()
    val id = BrowserView.key(qsid)
    if (id.isEmpty || id.length > 192 || !id.forall(c => c >= ' ' && c <= '~')) reject()
    val previous = sql(
      connection,
      "SELECT binding_hash,epoch,owner_id FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace=? AND view_id=? FOR UPDATE"
    ) { query =>
      scope(query); query.setString(3, id)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) None
        else Some((rows.getBytes(1).toVector, rows.getLong(2), Option(rows.getObject(3, classOf[UUID]))))
      }
    }
    if (existingOnly && previous.isEmpty) reject(OperationError.NotPrepared)
    if (previous.exists(_._1 != key(identity.binding))) reject()
    ClaimState(fresh, id, previous)
  }

  /** Reserve a server-generated HTTP bootstrap view without taking ownership. */
  override def registerView(identity: BrowserIdentity[P], qsid: Qsid)(using Effect[F]): F[Either[BrowserFailure, Unit]] =
    run { connection =>
      val state = claimState(connection, identity, qsid, existingOnly = false)
      if (state.previous.isEmpty) {
        if (count(connection, "spoonbill_browser_view", "cookie_namespace") >= limits.retainedViews)
          reject(OperationError.CapacityExceeded)
        sql(
          connection,
          """INSERT INTO spoonbill_browser_view(realm,cookie_namespace,view_id,binding_hash,owner_id,epoch)
          VALUES (?,?,?,?,NULL,1)"""
        ) { query =>
          scope(query); query.setString(3, state.id); query.setBytes(4, digest(identity.binding).bytes)
          query.executeUpdate(); ()
        }
      }
    }

  def claim(
    identity: BrowserIdentity[P],
    qsid: Qsid,
    connectionId: ConnectionId,
    existingOnly: Boolean
  ): F[Either[BrowserFailure, BrowserView[P]]] = {
    def acquire(admitted: Option[Option[Long]]): F[Either[BrowserFailure, BrowserView[P]]] = run { connection =>
      val state = claimState(connection, identity, qsid, existingOnly)
      if (admitted.exists(_ != state.previous.map(_._2))) reject()
      claimIn(connection, state, connectionId, qsid)
    }
    if (ownership == BrowserOwnership.ExclusiveScope) acquire(None)
    else
      F.flatMap(run { connection => claimState(connection, identity, qsid, existingOnly).previous.map(_._2) }) {
        case Left(error) => F.pure(Left(error))
        // This read is the durable admission boundary. If a takeover or release
        // wins before acquisition, a paused request may not steal ownership back.
        case Right(epoch) => acquire(Some(epoch))
      }
  }

  private def claimIn(
    connection: Connection,
    state: ClaimState,
    connectionId: ConnectionId,
    qsid: Qsid
  ): BrowserView[P] = {
    val ClaimState(identity, id, previous) = state
    if (previous.exists(_._2 == Long.MaxValue)) reject(OperationError.CapacityExceeded)
    if (previous.isEmpty && count(connection, "spoonbill_browser_view", "cookie_namespace") >= limits.retainedViews)
      reject(OperationError.CapacityExceeded)
    if (!previous.exists(_._3.nonEmpty)) {
      val active = sql(
        connection,
        "SELECT count(*) FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace=? AND binding_hash=? AND owner_id IS NOT NULL"
      ) { query =>
        scope(query); query.setBytes(3, digest(identity.binding).bytes)
        Using.resource(query.executeQuery()) { rows =>
          rows.next(); rows.getLong(1)
        }
      }
      if (active >= limits.activeViewsPerBinding) reject(OperationError.CapacityExceeded)
    }
    val epoch = previous.fold(1L)(_._2 + 1)
    val owner = newId()
    lifecycle.synchronized {
      if (ownedViews.size >= limits.retainedViews) reject(OperationError.CapacityExceeded)
      // Register before dispatch so unknown commit can still be conditionally
      // released. Failed claims are harmless candidates for final cleanup.
      ownedViews += (id -> owner) -> OwnedView(digest(identity.binding), owner, epoch)
    }
    sql(
      connection,
      """INSERT INTO spoonbill_browser_view(realm,cookie_namespace,view_id,binding_hash,owner_id,epoch)
      VALUES (?,?,?,?,?,?) ON CONFLICT(realm,cookie_namespace,view_id) DO UPDATE SET owner_id=EXCLUDED.owner_id,epoch=EXCLUDED.epoch"""
    ) { query =>
      scope(query); query.setString(3, id); query.setBytes(4, digest(identity.binding).bytes);
      query.setObject(5, owner); query.setLong(6, epoch); query.executeUpdate()
    }
    issuer.view(identity, qsid, connectionId, owner, epoch)
  }

  def authorize(view: BrowserView[P]): F[Either[BrowserFailure, BrowserIdentity[P]]] = run(current(_, view))

  /**
   * Admit one already materialized output under the same authoritative lock as
   * takeover, logout and host writes. No frame escapes an unknown settlement.
   * A frame admitted before takeover may still arrive afterward in transport.
   */
  override def admitOutput[A](view: BrowserView[P], output: A)(using Effect[F]): F[Either[BrowserFailure, A]] =
    run { connection => current(connection, view); output }

  override def admitOutputIf[A](view: BrowserView[P], output: A)(allowed: BrowserIdentity[P] => Boolean)(
    using Effect[F]
  ): F[Either[BrowserFailure, A]] = run { connection =>
    if (!allowed(current(connection, view))) reject()
    output
  }

  /**
   * Protected host writes recheck the exact captured authority inside their own
   * transaction. No execution authority is recovered from a ceremony ID.
   */
  def transact[A](view: BrowserView[P])(body: Tx => A): F[Either[TransactionFailure, A]] = owned() {
    transaction { connection =>
      current(connection, view); withHostTransaction(connection)(body)
    }
  }

  /** Recover explicitly selected, non-sensitive presentation under the current
   * authenticated view's durable authority and owner fence. Anonymous views
   * have no snapshot session. project and restore must be pure, nonblocking
   * transformations; restore overlays presentation onto freshly authorized
   * state and must not replace its authority or current business results.
   * Format decoders and compatibility conversions must likewise be pure and
   * bounded because reading may hold the provider transaction's locks.
   * An incompatible schema fails without replacing the stored projection.
   */
  def projectedSnapshots[S, D](
    format: spoonbill.snapshot.SnapshotFormat[D],
    project: S => D,
    restore: (S, D) => S
  ): BrowserView[P] => Option[spoonbill.snapshot.ViewSnapshotSession[F, S]] =
    JdbcBrowserSnapshots.projected(this, format, project, restore)

  /** Explicit administrative observation, never part of request authorization.
   * Counts share one authoritative transaction; applications control who may
   * see them. Local in-flight resources are bounded separately by pendingJobs.
   */
  def usage(): F[Either[TransactionFailure, BrowserPersistenceUsage]] = owned() {
    transaction { connection =>
      def views(active: Boolean): Long = sql(
        connection,
        "SELECT count(*) FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace=?" +
          (if (active) " AND owner_id IS NOT NULL" else "")
      ) { query =>
        scope(query)
        Using.resource(query.executeQuery()) { rows => rows.next(); rows.getLong(1) }
      }
      def maximum(table: String, namespaceColumn: String, condition: String): Long = sql(
        connection,
        s"""SELECT COALESCE(MAX(amount),0) FROM (SELECT count(*) AS amount FROM $table
        WHERE realm=? AND $namespaceColumn=? $condition GROUP BY binding_hash) AS bindings"""
      ) { query =>
        scope(query)
        Using.resource(query.executeQuery()) { rows => rows.next(); rows.getLong(1) }
      }
      BrowserPersistenceUsage(
        count(connection, "spoonbill_browser_slot", "cookie_namespace"),
        views(active = false),
        views(active = true),
        count(connection, "spoonbill_auth_ceremony"),
        count(connection, "spoonbill_auth_material"),
        maximum("spoonbill_browser_view", "cookie_namespace", "AND owner_id IS NOT NULL"),
        maximum("spoonbill_auth_ceremony", "namespace", ""),
        limits
      )
    }
  }

  /** Bounded administrative discovery; identifiers are lookup metadata only.
   * A page is current at its transaction boundary, not a lease for later work.
   */
  def ownedViewPage(
    after: Option[String] = None,
    limit: Int = 100
  ): F[Either[TransactionFailure, BrowserOwnedViewPage]] = owned() {
    transaction { connection =>
      if (limit <= 0 || limit > 1000 || after.exists(value =>
        value.isEmpty || value.length > 192 || !value.forall(c => c >= ' ' && c <= '~')
      )) reject(OperationError.InvalidDefinition)
      val found = sql(
        connection,
        """SELECT view_id,owner_id,epoch FROM spoonbill_browser_view
        WHERE realm=? AND cookie_namespace=? AND owner_id IS NOT NULL AND view_id>?
        ORDER BY view_id LIMIT ?"""
      ) { query =>
        scope(query); query.setString(3, after.getOrElse("")); query.setInt(4, limit + 1)
        Using.resource(query.executeQuery()) { rows =>
          val result = Vector.newBuilder[BrowserOwnedView]
          while (rows.next()) result += BrowserOwnedView(
            scope,
            rows.getString(1),
            rows.getObject(2, classOf[UUID]),
            ViewOwnershipEpoch.fromLong(rows.getLong(3)).fold(_ => reject(OperationError.StorageFailure), identity)
          )
          result.result()
        }
      }
      val page = found.take(limit)
      BrowserOwnedViewPage(page, Option.when(found.size > limit)(page.last.viewId))
    }
  }

  /** Privileged, explicit retirement of one observed owner. The host callback
   * must authorize the administrator using this transaction (and may audit the
   * attempt); false or a thrown rejection rolls all writes back. Target metadata
   * is never sufficient authorization. Returning false means its fence was no
   * longer current. A successful retirement retains the snapshot and permanent
   * view row while advancing the epoch; it does not reclaim history capacity.
   */
  def retireView(target: BrowserOwnedView)(authorize: Tx => Boolean): F[Either[TransactionFailure, Boolean]] =
    F.map(owned(cleanup = true) {
      transaction { connection =>
        if (target.scope != scope || target.viewId.isEmpty || target.viewId.length > 192 ||
          !target.viewId.forall(c => c >= ' ' && c <= '~') || target.epoch.toLong <= 0)
          reject(OperationError.InvalidDefinition)
        if (!withHostTransaction(connection)(authorize)) reject()
        val epoch = sql(
          connection,
          """SELECT epoch FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace=?
          AND view_id=? AND owner_id=? AND epoch=? FOR UPDATE"""
        ) { query =>
          scope(query); query.setString(3, target.viewId); query.setObject(4, target.ownerId)
          query.setLong(5, target.epoch.toLong)
          Using.resource(query.executeQuery()) { rows =>
            if (rows.next()) Some(rows.getLong(1)) else None
          }
        }
        epoch match {
          case None => false
          case Some(Long.MaxValue) => reject(OperationError.CapacityExceeded)
          case Some(currentEpoch) =>
            sql(
              connection,
              """UPDATE spoonbill_browser_view SET owner_id=NULL,epoch=? WHERE realm=? AND cookie_namespace=?
              AND view_id=? AND owner_id=? AND epoch=?"""
            ) { query =>
              query.setLong(1, currentEpoch + 1); query.setString(2, realm); query.setString(3, namespace)
              query.setString(4, target.viewId); query.setObject(5, target.ownerId); query.setLong(6, currentEpoch)
              if (query.executeUpdate() != 1) reject(OperationError.StorageFailure)
              true
            }
        }
      }
    }) { result =>
      if (result == Right(true)) lifecycle.synchronized {
        val key = target.viewId -> target.ownerId
        if (ownedViews.get(key).exists(_.epoch == target.epoch.toLong)) ownedViews -= key
      }
      result
    }

  def release(view: BrowserView[P]): F[Unit] = F.map(owned(cleanup = true) {
    transaction { connection =>
      if (issuer.owns(view))
        sql(
          connection,
          """UPDATE spoonbill_browser_view SET owner_id=NULL,
        epoch=CASE WHEN epoch<9223372036854775807 THEN epoch+1 ELSE epoch END
        WHERE realm=? AND cookie_namespace=? AND view_id=? AND binding_hash=? AND owner_id=? AND epoch=?"""
        ) { query =>
          scope(query); query.setString(3, BrowserView.key(view.qsid));
          query.setBytes(4, digest(view.identity.binding).bytes)
          query.setObject(5, view.ownerId); query.setLong(6, view.epoch); query.executeUpdate()
        }
      ()
    }
  }) {
    case Right(_) =>
      lifecycle.synchronized {
        val id = BrowserView.key(view.qsid) -> view.ownerId
        if (ownedViews.get(id).exists(_.epoch == view.epoch)) ownedViews -= id
      }
    case Left(TransactionFailure.Rejected(OperationError.ScopeClosed)) => ()
    case Left(_)                                                       => throw new JdbcAuthException(JdbcAuthError.StorageFailure)
  }

  private def readCeremony(connection: Connection, id: CeremonyId): BrowserCeremonyRecord =
    sql(
      connection,
      """SELECT attempt,binding_hash,generation,purpose,expires_at,phase,subject_key,credential_version,policy_version,challenge_reference
      FROM spoonbill_auth_ceremony WHERE realm=? AND namespace=? AND id=? FOR UPDATE"""
    ) { query =>
      scope(query); query.setObject(3, id.value)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) reject(OperationError.NotPrepared)
        val challenge = Option(rows.getString(7)).map(subject =>
          BrowserChallenge(subject, rows.getLong(8), rows.getLong(9), rows.getString(10))
        )
        BrowserCeremonyRecord(
          id,
          rows.getObject(1, classOf[UUID]),
          rows.getBytes(2).toVector,
          rows.getLong(3),
          rows.getString(4),
          rows.getTimestamp(5).toInstant,
          challenge,
          BrowserCeremonyPhase.valueOf(rows.getString(6))
        )
      }
    }
  private def save(connection: Connection, value: BrowserCeremonyRecord): Unit =
    sql(
      connection,
      """UPDATE spoonbill_auth_ceremony SET phase=?,subject_key=?,credential_version=?,policy_version=?,challenge_reference=?
      WHERE realm=? AND namespace=? AND id=?"""
    ) { query =>
      query.setString(1, value.phase.toString)
      query.setString(2, value.challenge.map(_.subject).orNull)
      query.setLong(3, value.challenge.fold(0L)(_.credentialVersion));
      query.setLong(4, value.challenge.fold(0L)(_.policyVersion))
      query.setString(5, value.challenge.map(_.reference).orNull)
      query.setString(6, realm); query.setString(7, namespace); query.setObject(8, value.id.value)
      if (query.executeUpdate() != 1) reject(OperationError.NotPrepared)
    }
  private def validate(record: BrowserCeremonyRecord, view: BrowserView[P], purpose: String): Unit =
    pureRequired(record.validate(key(view.identity.binding), view.identity.generation, purpose, now()))
  private def capability(view: BrowserView[P], record: BrowserCeremonyRecord): BrowserCeremony =
    issuer.ceremony(view, record.id, record.purpose, record.expiresAt, record.challenge)
  private def ceremonyView(value: BrowserCeremony): BrowserView[P] = {
    if (!issuer.owns(value)) reject(OperationError.ForeignPermit)
    value.view.asInstanceOf[BrowserView[P]]
  }

  def begin(view: BrowserView[P], purpose: String): F[Either[BrowserFailure, BrowserCeremony]] = run { connection =>
    if (purpose.isEmpty || purpose.length > 96) reject()
    current(connection, view)
    if (count(connection, "spoonbill_auth_ceremony") >= limits.retainedCeremonies)
      reject(OperationError.CapacityExceeded)
    val perBinding =
      sql(connection, "SELECT count(*) FROM spoonbill_auth_ceremony WHERE realm=? AND namespace=? AND binding_hash=?") {
        query =>
          scope(query); query.setBytes(3, digest(view.identity.binding).bytes)
          Using.resource(query.executeQuery()) { rows =>
            rows.next(); rows.getLong(1)
          }
      }
    if (perBinding >= limits.ceremoniesPerBinding) reject(OperationError.CapacityExceeded)
    val record = BrowserCeremonyRecord(
      CeremonyId(newId()),
      newId(),
      key(view.identity.binding),
      view.identity.generation,
      purpose,
      now().plusSeconds(limits.ceremonySeconds).truncatedTo(ChronoUnit.MICROS)
    )
    sql(
      connection,
      """INSERT INTO spoonbill_auth_ceremony(realm,namespace,id,attempt,binding_hash,generation,purpose,expires_at,phase)
      VALUES (?,?,?,?,?,?,?,?,'Begun')"""
    ) { query =>
      scope(query); query.setObject(3, record.id.value); query.setObject(4, record.completionId);
      query.setBytes(5, record.bindingKey.toArray)
      query.setLong(6, record.generation); query.setString(7, record.purpose);
      query.setTimestamp(8, Timestamp.from(record.expiresAt)); query.executeUpdate()
    }
    capability(view, record)
  }
  def bindChallenge(
    ceremony: BrowserCeremony,
    challenge: BrowserChallenge
  ): F[Either[BrowserFailure, BrowserCeremony]] = run { connection =>
    val view   = ceremonyView(ceremony)
    val record = readCeremony(connection, ceremony.id)
    current(connection, view); validate(record, view, ceremony.purpose)
    val next = pureRequired(record.bind(challenge))
    save(connection, next); capability(view, next)
  }
  def resume(view: BrowserView[P], id: CeremonyId, purpose: String): F[Either[BrowserFailure, BrowserCeremony]] = run {
    connection =>
      val record = readCeremony(connection, id)
      current(connection, view); validate(record, view, purpose)
      if (record.phase != BrowserCeremonyPhase.Begun && record.phase != BrowserCeremonyPhase.Challenged)
        reject(OperationError.PermitUsed)
      capability(view, record)
  }

  def pending(view: BrowserView[P]): F[Either[BrowserFailure, Option[BrowserContinuation]]] = run { connection =>
    if (!issuer.owns(view)) reject(OperationError.ForeignPermit)
    val time = now()
    val candidate = sql(
      connection,
      """SELECT c.id FROM spoonbill_auth_ceremony c
      WHERE c.realm=? AND c.namespace=? AND c.binding_hash=? AND c.generation=? AND c.expires_at>?
      AND (c.phase IN ('Challenged','Admitted') OR (c.phase='Committed' AND EXISTS (
        SELECT 1 FROM spoonbill_auth_material m WHERE m.realm=c.realm AND m.namespace=c.namespace
          AND m.attempt=c.attempt AND m.expires_at>? AND m.deliveries<?)))
      ORDER BY c.expires_at DESC,c.id DESC LIMIT 1"""
    ) { query =>
      scope(query); query.setBytes(3, digest(view.identity.binding).bytes); query.setLong(4, view.identity.generation)
      query.setTimestamp(5, Timestamp.from(time)); query.setTimestamp(6, Timestamp.from(time));
      query.setInt(7, limits.maxDeliveries)
      Using.resource(query.executeQuery()) { rows =>
        if (rows.next()) Some(CeremonyId(rows.getObject(1, classOf[UUID]))) else None
      }
    }
    val record = candidate.map(readCeremony(connection, _))
    current(connection, view)
    record.map { value =>
      validate(value, view, value.purpose)
      BrowserContinuation(
        value.id,
        value.purpose,
        value.challenge,
        value.phase == BrowserCeremonyPhase.Admitted || value.phase == BrowserCeremonyPhase.Committed,
        value.expiresAt
      )
    }
  }

  private val preparation = new BrowserPreparation[F, Direct, Connection, P](
    issuer,
    new TransactionExecutor[F, Direct, Connection] {
      val program                                                              = runner.program
      val scopePolicy                                                          = runner.scopePolicy
      def transact[A](body: Connection => A): F[Either[TransactionFailure, A]] = transaction(body)
    },
    ceremony =>
      transaction { connection =>
        val view   = ceremonyView(ceremony)
        val record = readCeremony(connection, ceremony.id)
        current(connection, view); validate(record, view, ceremony.purpose)
        if (record.challenge != ceremony.challenge) reject()
        save(connection, pureRequired(record.admit))
      }
  )

  def prepare(
    ceremony: BrowserCeremony
  )(consume: Tx => HostSessionPrepared): F[Either[TransactionFailure, CommittedAuthentication]] = owned() {
    preparation.prepare(ceremony) { connection =>
      val view   = ceremonyView(ceremony)
      val record = readCeremony(connection, ceremony.id)
      validate(record, view, ceremony.purpose)
      if (record.phase != BrowserCeremonyPhase.Admitted || record.challenge != ceremony.challenge)
        reject(OperationError.PermitUsed)
      // Proof/host-session work precedes framework slot/view locks. The
      // exact physical owner is rechecked before committing all writes.
      val prepared  = withHostTransaction(connection)(consume)
      val principal = host.resolve(connection, prepared.sessionId, now()).getOrElse(reject())
      if (
        record.challenge
          .exists(value => value.subject != principal.subject || value.policyVersion != principal.policyVersion)
      ) reject()
      current(connection, view)
      val time          = now()
      val sessionExpiry = prepared.expiresAt.truncatedTo(ChronoUnit.MICROS)
      if (!time.isBefore(sessionExpiry) || sessionExpiry.isAfter(principal.expiresAt)) reject(OperationError.Expired)
      validate(record, view, ceremony.purpose)
      retire(connection, time)
      if (count(connection, "spoonbill_auth_material") >= limits.deliveryMaterials)
        reject(OperationError.CapacityExceeded)
      val deadline = Vector(record.expiresAt, sessionExpiry, time.plusSeconds(limits.deliverySeconds)).min
        .truncatedTo(ChronoUnit.MICROS)
      browser.prepareNew(
        connection,
        PreparedBrowserSession(
          record.completionId,
          prepared.completionId,
          prepared.sessionId,
          digest(view.identity.binding),
          digest(prepared.credential.value),
          record.generation,
          sessionExpiry,
          deadline
        )
      )
      val plaintext = prepared.credential.value.getBytes(UTF_8)
      val ciphertext =
        try vault.seal(plaintext, associated(record.completionId, record.bindingKey))
        finally java.util.Arrays.fill(plaintext, 0.toByte)
      sql(
        connection,
        "INSERT INTO spoonbill_auth_material(realm,namespace,attempt,payload,deliveries,expires_at) VALUES (?,?,?,?,0,?)"
      ) { query =>
        scope(query); query.setObject(3, record.completionId); query.setBytes(4, ciphertext);
        query.setTimestamp(5, Timestamp.from(deadline)); query.executeUpdate()
      }
      validate(record, view, ceremony.purpose)
      save(connection, pureRequired(record.committed))
      record.completionId
    }
  }

  def recover(view: BrowserView[P], id: CeremonyId): F[Either[BrowserFailure, BrowserRecovery]] = run { connection =>
    val record = readCeremony(connection, id)
    if (record.phase == BrowserCeremonyPhase.Committed) {
      val prepared = sql(
        connection,
        """SELECT s.host_session_id,s.expires_at,c.expires_at,m.expires_at,m.deliveries
        FROM spoonbill_browser_completion c JOIN spoonbill_browser_session s
          ON s.realm=c.realm AND s.cookie_namespace=c.cookie_namespace AND s.token_hash=c.token_hash
        JOIN spoonbill_auth_material m ON m.realm=c.realm AND m.namespace=c.cookie_namespace AND m.attempt=c.completion_id
        WHERE c.realm=? AND c.cookie_namespace=? AND c.completion_id=? AND c.acknowledged_at IS NULL AND NOT s.revoked"""
      ) { query =>
        scope(query); query.setObject(3, record.completionId)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next()) reject(OperationError.Expired)
          (
            rows.getObject(1, classOf[UUID]),
            Vector(rows.getTimestamp(2).toInstant, rows.getTimestamp(3).toInstant, rows.getTimestamp(4).toInstant),
            rows.getInt(5)
          )
        }
      }
      val principal = host.resolve(connection, prepared._1, now()).getOrElse(reject())
      val time      = now()
      if (
        prepared._2.exists(deadline => !time.isBefore(deadline)) || !time.isBefore(
          principal.expiresAt
        ) || prepared._3 >= limits.maxDeliveries
      )
        reject(OperationError.Expired)
    }
    current(connection, view)
    validate(record, view, record.purpose)
    val next = record.reconciled
    if (next.phase == BrowserCeremonyPhase.Committed) BrowserRecovery.Committed(issuer.committed(record.completionId))
    else { save(connection, next); BrowserRecovery.NotCommitted }
  }

  private def associated(attempt: UUID, binding: Vector[Byte]): Array[Byte] =
    s"${realm.length}:$realm${namespace.length}:$namespace$attempt:".getBytes(UTF_8) ++ binding.toArray

  def deliver(completionId: UUID, binding: String): F[Option[BrowserSessionToken]] = F.map(run { connection =>
    if (!validSecret(binding)) reject()
    val recordId =
      sql(connection, "SELECT id FROM spoonbill_auth_ceremony WHERE realm=? AND namespace=? AND attempt=?") { query =>
        scope(query); query.setObject(3, completionId)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next()) reject(OperationError.NotPrepared); CeremonyId(rows.getObject(1, classOf[UUID]))
        }
      }
    val record = readCeremony(connection, recordId)
    if (record.bindingKey != key(binding) || record.phase != BrowserCeremonyPhase.Committed) reject()
    val tokenRow = sql(
      connection,
      """SELECT s.host_session_id,s.token_hash,s.expires_at,c.expires_at,c.acknowledged_at,s.revoked
      FROM spoonbill_browser_session s JOIN spoonbill_browser_completion c ON s.realm=c.realm
      AND s.cookie_namespace=c.cookie_namespace AND s.token_hash=c.token_hash
      WHERE c.realm=? AND c.cookie_namespace=? AND c.completion_id=?"""
    ) { query =>
      scope(query); query.setObject(3, completionId)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) reject(OperationError.NotPrepared)
        (
          rows.getObject(1, classOf[UUID]),
          rows.getBytes(2).toVector,
          rows.getTimestamp(3).toInstant,
          rows.getTimestamp(4).toInstant,
          rows.getTimestamp(5) != null || rows.getBoolean(6)
        )
      }
    }
    val principal = host.resolve(connection, tokenRow._1, now()).getOrElse(reject())
    val generation = sql(
      connection,
      "SELECT generation FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace=? AND binding_hash=? FOR UPDATE"
    ) { query =>
      scope(query); query.setBytes(3, digest(binding).bytes)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) reject(); rows.getLong(1)
      }
    }
    val time = now()
    pureRequired(record.validate(key(binding), generation, record.purpose, time))
    if (
      tokenRow._5 || !time.isBefore(tokenRow._3) || !time.isBefore(tokenRow._4) || !time.isBefore(principal.expiresAt)
    ) reject(OperationError.Expired)
    val encrypted = sql(
      connection,
      "SELECT payload,deliveries,expires_at FROM spoonbill_auth_material WHERE realm=? AND namespace=? AND attempt=? FOR UPDATE"
    ) { query =>
      scope(query); query.setObject(3, completionId)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) reject(OperationError.NotPrepared)
        if (rows.getInt(2) >= limits.maxDeliveries || !time.isBefore(rows.getTimestamp(3).toInstant))
          reject(OperationError.Expired)
        rows.getBytes(1) -> (rows.getInt(2) + 1)
      }
    }
    val plaintext = vault.open(encrypted._1, associated(completionId, record.bindingKey))
    val credential =
      try {
        val value = new String(plaintext, UTF_8)
        if (key(value) != tokenRow._2) reject()
        BrowserSessionToken.fromString(value).fold(_ => reject(), identity)
      } finally java.util.Arrays.fill(plaintext, 0.toByte)
    if (encrypted._2 >= limits.maxDeliveries)
      sql(connection, "DELETE FROM spoonbill_auth_material WHERE realm=? AND namespace=? AND attempt=?") { query =>
        scope(query); query.setObject(3, completionId); query.executeUpdate()
      }
    else
      sql(connection, "UPDATE spoonbill_auth_material SET deliveries=? WHERE realm=? AND namespace=? AND attempt=?") {
        query =>
          query.setInt(1, encrypted._2); query.setString(2, realm); query.setString(3, namespace);
          query.setObject(4, completionId); query.executeUpdate()
      }
    credential
  })(_.toOption)

  def logout(binding: String, token: Option[String]): F[Either[BrowserFailure, Unit]] = run { connection =>
    if (!validSecret(binding)) reject()
    browser.logoutIn(connection, digest(binding))
    sql(
      connection,
      """DELETE FROM spoonbill_auth_material m USING spoonbill_auth_ceremony c
      WHERE m.realm=? AND m.namespace=? AND c.realm=m.realm AND c.namespace=m.namespace AND c.attempt=m.attempt AND c.binding_hash=?"""
    ) { query =>
      scope(query); query.setBytes(3, digest(binding).bytes); query.executeUpdate(); ()
    }
  }
  private def retire(connection: Connection, time: Instant): Unit = sql(
    connection,
    """DELETE FROM spoonbill_auth_material m USING spoonbill_browser_completion c,spoonbill_browser_session s,spoonbill_browser_slot b
      WHERE m.realm=? AND m.namespace=? AND c.realm=m.realm AND c.cookie_namespace=m.namespace AND c.completion_id=m.attempt
      AND s.realm=c.realm AND s.cookie_namespace=c.cookie_namespace AND s.token_hash=c.token_hash
      AND b.realm=s.realm AND b.cookie_namespace=s.cookie_namespace AND b.binding_hash=s.binding_hash
      AND (m.expires_at<=? OR c.expires_at<=? OR s.expires_at<=? OR s.revoked OR c.acknowledged_at IS NOT NULL OR b.generation<>s.origin_generation)"""
  ) { query =>
    scope(query); (3 to 5).foreach(index => query.setTimestamp(index, Timestamp.from(time))); query.executeUpdate(); ()
  }
  private def retirePolicy(connection: Connection): Unit = {
    val candidates = sql(
      connection,
      """SELECT m.attempt,s.host_session_id FROM spoonbill_auth_material m
      JOIN spoonbill_browser_completion c ON c.realm=m.realm AND c.cookie_namespace=m.namespace AND c.completion_id=m.attempt
      JOIN spoonbill_browser_session s ON s.realm=c.realm AND s.cookie_namespace=c.cookie_namespace AND s.token_hash=c.token_hash
      WHERE m.realm=? AND m.namespace=?"""
    ) { query =>
      scope(query)
      Using.resource(query.executeQuery()) { rows =>
        val result = Vector.newBuilder[(UUID, UUID)]
        while (rows.next()) result += rows.getObject(1, classOf[UUID]) -> rows.getObject(2, classOf[UUID])
        result.result()
      }
    }
    candidates.foreach { case (attempt, session) =>
      val principal = host.resolve(connection, session, now())
      val time      = now()
      if (!principal.exists(value => time.isBefore(value.expiresAt)))
        sql(connection, "DELETE FROM spoonbill_auth_material WHERE realm=? AND namespace=? AND attempt=?") { query =>
          scope(query); query.setObject(3, attempt); query.executeUpdate()
        }
    }
  }
  def maintenance(): F[Unit] = F.flatMap(F.delay(maintaining.compareAndSet(false, true))) {
    case false => F.unit
    case true =>
      F.recoverF(F.map(owned(cleanup = true)(transaction { connection =>
        retire(connection, now()); retirePolicy(connection)
      })) { result =>
        maintaining.set(false)
        result match {
          case Right(_) | Left(TransactionFailure.Rejected(OperationError.ScopeClosed)) => ()
          case Left(_)                                                                  => throw new JdbcAuthException(JdbcAuthError.StorageFailure)
        }
      }) { case NonFatal(error) => maintaining.set(false); F.fail(error) }
  }
  def close(): F[Unit] = F.uncancelable {
    val drained = F.promise[Unit] { callback =>
      val finished = lifecycle.synchronized {
        stopped = true
        if (activeOperations == 0 && cleanupPending == 0) true
        else { closeWaiters = callback :: closeWaiters; false }
      }
      if (finished) callback(Right(()))
    }
    F.flatMap(drained) { _ =>
      F.flatMap(F.delay(closing.compareAndSet(false, true))) {
        case false =>
          F.promise(callback => closed.future.onComplete(value => callback(value.toEither))(ExecutionContext.parasitic))
        case true =>
          F.recoverF(F.map(F.blocking {
            val retained = lifecycle.synchronized(ownedViews)
            if (retained.nonEmpty)
              JdbcTransactions
                .run[JdbcAuthError, Unit](source, JdbcAuthError.StorageFailure, JdbcAuthError.StorageFailure) {
                  connection =>
                    realmLock(connection)
                    retained.foreach { case ((id, _), value) =>
                      sql(
                        connection,
                        """UPDATE spoonbill_browser_view SET owner_id=NULL,
                  epoch=CASE WHEN epoch<9223372036854775807 THEN epoch+1 ELSE epoch END
                  WHERE realm=? AND cookie_namespace=? AND view_id=? AND binding_hash=? AND owner_id=? AND epoch=?"""
                      ) { query =>
                        scope(query); query.setString(3, id); query.setBytes(4, value.binding.bytes)
                        query.setObject(5, value.owner); query.setLong(6, value.epoch); query.executeUpdate()
                      }
                    }
                    Right(())
                }
                .fold(error => throw new JdbcAuthException(error), identity)
          }(blockingContext)) { _ =>
            lifecycle.synchronized { ownedViews = Map.empty }
            issuer.close(); closed.trySuccess(()); ()
          }) { case NonFatal(error) =>
            issuer.close(); closed.tryFailure(error); F.fail(error)
          }
      }
    }
  }

  private val schema = Vector(
    """CREATE TABLE IF NOT EXISTS spoonbill_auth_ceremony (
      realm VARCHAR(96) NOT NULL, namespace VARCHAR(96) NOT NULL, id UUID NOT NULL, attempt UUID NOT NULL,
      binding_hash BYTEA NOT NULL CHECK(octet_length(binding_hash)=32), generation BIGINT NOT NULL CHECK(generation>=0),
      purpose VARCHAR(96) NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
      phase VARCHAR(16) NOT NULL CHECK(phase IN ('Begun','Challenged','Admitted','Committed','NotCommitted')),
      subject_key VARCHAR(256), credential_version BIGINT, policy_version BIGINT, challenge_reference VARCHAR(256),
      PRIMARY KEY(realm,namespace,id), UNIQUE(realm,namespace,attempt))""",
    """CREATE INDEX IF NOT EXISTS spoonbill_auth_ceremony_binding ON spoonbill_auth_ceremony(realm,namespace,binding_hash)""",
    """CREATE TABLE IF NOT EXISTS spoonbill_auth_material (
      realm VARCHAR(96) NOT NULL, namespace VARCHAR(96) NOT NULL, attempt UUID NOT NULL, payload BYTEA NOT NULL,
      deliveries INTEGER NOT NULL CHECK(deliveries>=0), expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
      PRIMARY KEY(realm,namespace,attempt), FOREIGN KEY(realm,namespace,attempt)
        REFERENCES spoonbill_auth_ceremony(realm,namespace,attempt))"""
  )
}
