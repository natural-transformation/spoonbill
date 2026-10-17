package example.browserauth

import java.security.MessageDigest
import java.sql.{Connection, PreparedStatement, Timestamp}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import javax.sql.DataSource
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Using
import scala.util.control.NonFatal
import spoonbill.action.{AuthenticatedActionContext, UiOutcome}
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.security.jdbc.*
import spoonbill.security.transaction.*
import spoonbill.sensitive.{Audience, Purpose, SensitiveAccess}
import spoonbill.server.SessionAccessDenied
import spoonbill.snapshot.{ViewSnapshotError, ViewSnapshotException, ViewSnapshotSession}

/**
 * Synthetic account policy and SQL. Browser ceremonies, cookie custody,
 * settlement, recovery, ownership and presentation storage are library code.
 * The server owns the disposable data source, executor and explicit vault key.
 */
final class JdbcAccounts(
  source: DataSource,
  blockingContext: ExecutionContext,
  origin: String,
  vault: JdbcCredentialVault,
  clock: () => Instant = () => Instant.now(),
  clustered: Boolean = false,
  snapshotVersion: Int = 1,
  activeViewLimit: Int = 16,
  fixtureDiagnostics: Boolean = false,
  deliveryLimit: Int = 3
)(using ec: ExecutionContext)
    extends Accounts[Future] {
  private case class Account(
    id: UUID,
    name: String,
    version: Long,
    enabled: Boolean,
    password: Vector[Byte],
    factor: Option[Vector[Byte]]
  )
  private val names           = DomainPolicy.accounts.map(value => value.name -> value.id).toMap
  private val rate            = new DomainPolicy.RateLimit(clock)
  private val recoveryResults = new RecoveryResults(vault, clock)
  private val jobs            = new AtomicInteger()
  private val stopped         = new AtomicBoolean()
  private val drained         = Promise[Unit]()
  private def deny(): Nothing = throw new OperationProtocolException(OperationError.HostDenied)
  private def sql[A](connection: Connection, text: String)(body: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(text))(body)
  private def account(connection: Connection, id: UUID, lock: Boolean): Account =
    sql(
      connection,
      "SELECT name,version,enabled,password_hash,factor_hash FROM example_account WHERE id=?" + (if (lock) " FOR UPDATE"
                                                                                                 else "")
    ) { query =>
      query.setObject(1, id)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) deny()
        Account(
          id,
          rows.getString(1),
          rows.getLong(2),
          rows.getBoolean(3),
          rows.getBytes(4).toVector,
          Option(rows.getBytes(5)).map(_.toVector)
        )
      }
    }
  private val host = new JdbcHostSessions[Principal] {
    def resolve(connection: Connection, sessionId: UUID, now: Instant): Option[HostPrincipal[Principal]] = {
      val subject = sql(connection, "SELECT subject FROM example_session WHERE id=?") { query =>
        query.setObject(1, sessionId)
        Using.resource(query.executeQuery()) { rows =>
          if (rows.next()) Some(rows.getObject(1, classOf[UUID])) else None
        }
      }
      subject.flatMap { id =>
        val current = account(connection, id, lock = true)
        sql(connection, "SELECT subject,version,enabled,expires_at FROM example_session WHERE id=? FOR UPDATE") {
          query =>
            query.setObject(1, sessionId)
            Using.resource(query.executeQuery()) { rows =>
              if (
                !rows.next() || rows.getObject(1, classOf[UUID]) != id || !current.enabled ||
                !rows.getBoolean(3) || rows
                  .getLong(2) != current.version || !now.isBefore(rows.getTimestamp(4).toInstant)
              ) None
              else
                Some(
                  HostPrincipal(
                    Principal(id, current.name),
                    id.toString,
                    current.version,
                    current.version,
                    rows.getTimestamp(4).toInstant
                  )
                )
            }
        }
      }
    }
    def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit =
      sql(connection, "UPDATE example_session SET acknowledged=TRUE WHERE id=? AND completion=?") { query =>
        query.setObject(1, sessionId); query.setObject(2, completionId)
        if (query.executeUpdate() != 1) deny()
      }
  }
  val persistence = new JdbcBrowserPersistence[Future, Principal](
    source,
    blockingContext,
    "example",
    "session",
    clock,
    host,
    vault,
    ownership = if (clustered) BrowserOwnership.SharedViews else BrowserOwnership.ExclusiveScope,
    limits = BrowserPersistenceLimits(activeViewsPerBinding = activeViewLimit, maxDeliveries = deliveryLimit)
  )
  private val projected = persistence.projectedSnapshots(
    DraftPresentation.format(snapshotVersion),
    DraftPresentation.project,
    DraftPresentation.restore
  )
  private def snapshots(view: BrowserView[Principal]): Option[ViewSnapshotSession[Future, Page]] =
    if (!clustered) None
    else
      projected(view).map { session =>
        if (!fixtureDiagnostics) session
        else
          new ViewSnapshotSession[Future, Page] {
            def ownerEpoch = session.ownerEpoch
            def initialize(fresh: Page): Future[Page] = session.initialize(fresh).recoverWith {
              case error: ViewSnapshotException if error.error == ViewSnapshotError.SchemaIncompatible =>
                System.err.println("SPOONBILL_FIXTURE_SCHEMA_INCOMPATIBLE")
                Future.failed(error)
            }
            def commit(candidate: Page): Future[Unit] = session.commit(candidate)
          }
      }
  val browser = new BrowserAuthentication[Future, Page, Principal](
    persistence,
    Presentation.policy,
    BrowserConfiguration(
      BrowserProviderScope("example", "session"),
      Set(origin),
      secureCookies = origin.startsWith("https://")
    ),
    clock = clock,
    snapshots = snapshots,
    sensitiveAccess = view =>
      view.identity.sessionId.map { session =>
        new SensitiveAccess[Future, Page] {
          def authorize(purpose: Purpose, page: Page): Future[Audience] =
            persistence.authorize(view).flatMap {
              case Right(current) if purpose == RecoveryResults.purpose && current.sessionId.contains(session) =>
                Future.successful(Audience.fromUuid(session))
              case _ => Future.failed(new SessionAccessDenied)
            }
        }
      }
  )

  /**
   * Bounded before any proof-worker/database dispatch, including failed work.
   */
  @scala.annotation.tailrec
  private def reserve(): Boolean = {
    val current = jobs.get()
    if (stopped.get() || current >= 256) false
    else if (jobs.compareAndSet(current, current + 1)) {
      if (stopped.get()) { releaseJob(); false }
      else true
    } else reserve()
  }
  private def releaseJob(): Unit = {
    if (jobs.decrementAndGet() == 0 && stopped.get()) drained.trySuccess(())
    ()
  }
  private def bounded[A](rejected: => A, unresolved: => A)(body: => Future[A]): Future[A] =
    if (!reserve()) Future.successful(rejected)
    else {
      val work =
        try body
        catch { case NonFatal(error) => Future.failed(error) }
      work.transform { result =>
        releaseJob()
        scala.util.Success(result.getOrElse(unresolved))
      }(ExecutionContext.parasitic)
    }
  private def submitted(ceremony: BrowserCeremony)(body: => Future[SignIn]): Future[SignIn] =
    bounded(SignIn.Rejected("Too many pending requests."), SignIn.Unresolved(ceremony.id))(body)
  private def result(ceremony: BrowserCeremony, value: Either[TransactionFailure, CommittedAuthentication]): SignIn =
    value match {
      case Right(receipt)                                                             => SignIn.Completed(receipt)
      case Left(TransactionFailure.CommitUnknown | TransactionFailure.StorageFailure) => SignIn.Unresolved(ceremony.id)
      case Left(TransactionFailure.Rejected(OperationError.PermitUsed | OperationError.EvidenceUsed)) =>
        SignIn.Unresolved(ceremony.id)
      case Left(_) => SignIn.Rejected("The sign-in was not accepted. Begin a new sign-in.")
    }
  private def audit(connection: Connection, subject: UUID, kind: String): Unit = {
    val count = sql(connection, "SELECT count(*) FROM example_audit") { query =>
      Using.resource(query.executeQuery()) { rows =>
        rows.next(); rows.getLong(1)
      }
    }
    if (count >= DomainPolicy.auditRecords) throw new OperationProtocolException(OperationError.CapacityExceeded)
    sql(connection, "INSERT INTO example_audit(id,subject,kind,created_at) VALUES (?,?,?,?)") { query =>
      query.setObject(1, UUID.randomUUID()); query.setObject(2, subject); query.setString(3, kind)
      query.setTimestamp(4, Timestamp.from(clock())); query.executeUpdate(); ()
    }
  }
  private def complete(ceremony: BrowserCeremony, expected: Account, factor: Option[Vector[Byte]]): Future[SignIn] =
    BrowserCredentials.generate[Future].flatMap { credential =>
      persistence
        .prepare(ceremony) { tx =>
          val connection = tx.connection
          val current    = account(connection, expected.id, lock = true)
          if (
            !current.enabled || current.version != expected.version || current.password != expected.password || ceremony.purpose != "sign-in"
          ) deny()
          (ceremony.challenge, factor, current.factor) match {
            case (None, None, None) => ()
            case (Some(challenge), Some(submitted), Some(stored))
                if challenge.subject == current.id.toString && challenge.credentialVersion == current.version &&
                  challenge.policyVersion == current.version && MessageDigest
                    .isEqual(submitted.toArray, stored.toArray) &&
                  DomainPolicy.challengeDeadline(challenge.reference).exists(tx.now.isBefore) =>
              ()
            case _ => deny()
          }
          sql(connection, "INSERT INTO example_consumed_proof(ceremony,subject,challenge_reference) VALUES (?,?,?)") {
            query =>
              query.setObject(1, ceremony.id.value); query.setObject(2, current.id)
              query.setString(3, ceremony.challenge.map(_.reference).orNull); query.executeUpdate()
          }
          val session    = UUID.randomUUID()
          val completion = UUID.randomUUID()
          val expires =
            tx.now.plusSeconds(DomainPolicy.sessionSeconds).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
          sql(
            connection,
            "INSERT INTO example_session(id,completion,subject,version,enabled,expires_at) VALUES (?,?,?,?,TRUE,?)"
          ) { query =>
            query.setObject(1, session); query.setObject(2, completion); query.setObject(3, current.id)
            query.setLong(4, current.version); query.setTimestamp(5, Timestamp.from(expires)); query.executeUpdate()
          }
          audit(connection, current.id, "login")
          new HostSessionPrepared(session, completion, expires, credential)
        }
        .map(result(ceremony, _))
    }

  def signIn(ceremony: BrowserCeremony, username: String, password: String): Future[SignIn] = submitted(ceremony) {
    names.get(username) match {
      case None => Future.successful(SignIn.Rejected("The sign-in was not accepted."))
      case Some(id) if !rate.admit(ceremony.browserKey, username) =>
        Future.successful(SignIn.Rejected("Too many attempts. Wait before trying again."))
      case Some(id) if ceremony.challenge.nonEmpty => Future.successful(SignIn.Unresolved(ceremony.id))
      case Some(id) =>
        Future {
          val observed = Using.resource(source.getConnection)(connection => account(connection, id, lock = false))
          val verified = observed.enabled && MessageDigest.isEqual(
            DomainPolicy.passwordHash(password).toArray,
            observed.password.toArray
          )
          observed -> verified
        }(blockingContext).flatMap { case (observed, verified) =>
          if (!verified) Future.successful(SignIn.Rejected("The sign-in was not accepted."))
          else
            observed.factor match {
              case None => complete(ceremony, observed, None)
              case Some(_) =>
                val challenge = BrowserChallenge(
                  observed.id.toString,
                  observed.version,
                  observed.version,
                  DomainPolicy.challengeReference(clock(), ceremony.expiresAt)
                )
                persistence.bindChallenge(ceremony, challenge).map {
                  case Right(bound) => SignIn.Challenge(bound.id, challenge.reference)
                  case Left(
                        BrowserFailure.Settlement(TransactionFailure.CommitUnknown | TransactionFailure.StorageFailure)
                      ) =>
                    SignIn.Unresolved(ceremony.id)
                  case Left(_) => SignIn.Rejected("This sign-in could not continue. Begin a new sign-in.")
                }
            }
        }
    }
  }
  def factor(ceremony: BrowserCeremony, value: String): Future[SignIn] = submitted(ceremony) {
    ceremony.challenge match {
      case None => Future.successful(SignIn.Rejected("The original factor challenge is required."))
      case Some(challenge) if !rate.admit(ceremony.browserKey, challenge.subject) =>
        Future.successful(SignIn.Rejected("Too many attempts. Wait before trying again."))
      case Some(challenge) =>
        Future {
          val id       = UUID.fromString(challenge.subject)
          val observed = Using.resource(source.getConnection)(connection => account(connection, id, lock = false))
          observed -> DomainPolicy.factorHash(value)
        }(blockingContext).flatMap { case (observed, proof) =>
          if (
            !observed.enabled || observed.version != challenge.credentialVersion ||
            !observed.factor.exists(stored => MessageDigest.isEqual(stored.toArray, proof.toArray))
          )
            Future.successful(SignIn.Rejected("The factor was not accepted."))
          else complete(ceremony, observed, Some(proof))
        }
    }
  }
  def replace(
    ceremony: BrowserReplacementCeremony,
    invocation: BrowserReplacementInvocation,
    password: String
  ): Future[SessionReplacement] =
    bounded[SessionReplacement](
      SessionReplacement.Rejected("Too many pending requests."),
      SessionReplacement.Unresolved(ceremony.id)
    ) {
      val change  = SecurityChange.values.find(_.purpose == ceremony.purpose)
      val subject = invocation.authority.host.flatMap(stamp => scala.util.Try(UUID.fromString(stamp.subject)).toOption)
      if (
        change.isEmpty || subject.isEmpty || invocation.authority.sessionId.isEmpty ||
        !rate.admit(ceremony.browserKey, subject.get.toString)
      )
        Future.successful(SessionReplacement.Rejected("Security change was not accepted."))
      else
        Future {
          val observed =
            Using.resource(source.getConnection)(connection => account(connection, subject.get, lock = false))
          val stamp = invocation.authority.host.get
          if (!observed.enabled || observed.version != stamp.securityVersion || observed.version != stamp.policyVersion)
            deny()
          observed
        }(blockingContext).flatMap { observed =>
          val challenge = BrowserChallenge(
            observed.id.toString,
            observed.version,
            observed.version,
            DomainPolicy.challengeReference(clock(), ceremony.expiresAt)
          )
          persistence.bindReplacementChallenge(ceremony, challenge).flatMap {
            case Left(_) =>
              Future.successful(SessionReplacement.Rejected("This security change expired or was revoked."))
            case Right(bound) =>
              Future(DomainPolicy.same(DomainPolicy.passwordHash(password), observed.password))(
                blockingContext
              ).flatMap { verified =>
                if (!verified) Future.successful(SessionReplacement.Rejected("Password was not accepted."))
                else
                  BrowserCredentials.generate[Future].flatMap { credential =>
                    val session    = UUID.randomUUID()
                    val completion = UUID.randomUUID()
                    val generated = Option.when(change.get != SecurityChange.DisableFactor)(
                      recoveryResults.create(completion, session, clock())
                    )
                    persistence
                      .prepareReplacement(bound, invocation) { tx =>
                        val connection = tx.connection
                        val current    = account(connection, observed.id, lock = true)
                        val time       = tx.now
                        if (
                          !current.enabled || current.version != observed.version || current.password != observed.password ||
                          current.version == Long.MaxValue || bound.challenge != Some(challenge) ||
                          !DomainPolicy.challengeDeadline(challenge.reference).exists(time.isBefore) ||
                          generated.exists(value => !time.isBefore(value.record.expiresAt))
                        ) deny()
                        val factor = change.get match {
                          case SecurityChange.RegenerateRecovery => current.factor
                          case SecurityChange.EnableFactor if current.factor.isEmpty =>
                            Some(DomainPolicy.factorHash("123456"))
                          case SecurityChange.ReplaceFactor if current.factor.nonEmpty =>
                            Some(DomainPolicy.factorHash("654321"))
                          case SecurityChange.DisableFactor if current.factor.nonEmpty => None
                          case _                                                       => deny()
                        }
                        sql(
                          connection,
                          "INSERT INTO example_consumed_proof(ceremony,subject,challenge_reference) VALUES (?,?,?)"
                        ) { query =>
                          query.setObject(1, bound.id.value); query.setObject(2, current.id);
                          query.setString(3, challenge.reference)
                          query.executeUpdate()
                        }
                        sql(
                          connection,
                          "UPDATE example_account SET version=version+1,factor_hash=? WHERE id=? AND version=?"
                        ) { query =>
                          query.setBytes(1, factor.map(_.toArray).orNull); query.setObject(2, current.id);
                          query.setLong(3, current.version)
                          if (query.executeUpdate() != 1) deny()
                        }
                        sql(
                          connection,
                          "UPDATE example_session SET enabled=FALSE WHERE id=? AND subject=? AND enabled=TRUE"
                        ) { query =>
                          query.setObject(1, invocation.authority.sessionId.get); query.setObject(2, current.id)
                          if (query.executeUpdate() != 1) deny()
                        }
                        val expires = time
                          .plusSeconds(DomainPolicy.sessionSeconds)
                          .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                        sql(
                          connection,
                          "INSERT INTO example_session(id,completion,subject,version,enabled,expires_at) VALUES (?,?,?,?,TRUE,?)"
                        ) { query =>
                          query.setObject(1, session); query.setObject(2, completion); query.setObject(3, current.id)
                          query.setLong(4, current.version + 1L); query.setTimestamp(5, Timestamp.from(expires));
                          query.executeUpdate()
                        }
                        sql(connection, "DELETE FROM example_recovery_code WHERE subject=?") { query =>
                          query.setObject(1, current.id); query.executeUpdate()
                        }
                        sql(
                          connection,
                          "DELETE FROM example_recovery_result WHERE expires_at<=? OR session IN (SELECT id FROM example_session WHERE subject=?)"
                        ) { query =>
                          query.setTimestamp(1, Timestamp.from(time)); query.setObject(2, current.id);
                          query.executeUpdate()
                        }
                        generated.foreach { value =>
                          val count = sql(connection, "SELECT count(*) FROM example_recovery_result") { query =>
                            Using.resource(query.executeQuery()) { rows =>
                              rows.next(); rows.getLong(1)
                            }
                          }
                          if (count >= DomainPolicy.retainedEntries)
                            throw new OperationProtocolException(OperationError.CapacityExceeded)
                          value.hashes.foreach { hash =>
                            sql(connection, "INSERT INTO example_recovery_code(subject,digest) VALUES (?,?)") { query =>
                              query.setObject(1, current.id); query.setBytes(2, hash.toArray); query.executeUpdate()
                            }
                          }
                          sql(
                            connection,
                            "INSERT INTO example_recovery_result(completion,session,expires_at,encrypted) VALUES (?,?,?,?)"
                          ) { query =>
                            query.setObject(1, completion); query.setObject(2, session)
                            query.setTimestamp(3, Timestamp.from(value.record.expiresAt));
                            query.setBytes(4, value.record.ciphertext.toArray)
                            query.executeUpdate()
                          }
                        }
                        audit(connection, current.id, change.get.purpose)
                        new HostSessionPrepared(session, completion, expires, credential)
                      }
                      .map {
                        case Right(handoff) => SessionReplacement.Completed(handoff)
                        case Left(TransactionFailure.CommitUnknown | TransactionFailure.StorageFailure) =>
                          SessionReplacement.Unresolved(bound.id)
                        case Left(
                              TransactionFailure.Rejected(OperationError.PermitUsed | OperationError.EvidenceUsed)
                            ) =>
                          SessionReplacement.Unresolved(bound.id)
                        case Left(_) =>
                          SessionReplacement.Rejected("Security change was not accepted. Begin a new attempt.")
                      }
                  }
              }
          }
        }
    }

  def consumeRecovery(
    view: BrowserView[Principal],
    invocation: BrowserReplacementInvocation
  ): Future[Either[TransactionFailure, Option[RecoveryResults.Consumed]]] =
    if (!invocation.matchesView(view))
      Future.successful(Left(TransactionFailure.Rejected(OperationError.ForeignPermit)))
    else
      persistence
        .withSuccessorCompletion(view) { (tx, completion) =>
          sql(
            tx.connection,
            "DELETE FROM example_recovery_result WHERE completion=? AND session=? AND expires_at>? RETURNING expires_at,encrypted"
          ) { query =>
            query.setObject(1, completion.hostCompletionId); query.setObject(2, completion.successorSessionId)
            query.setTimestamp(3, Timestamp.from(tx.now))
            Using.resource(query.executeQuery()) { rows =>
              if (!rows.next()) None
              else {
                val expires = rows.getTimestamp(1).toInstant
                if (!tx.now.isBefore(expires)) throw new OperationProtocolException(OperationError.Expired)
                Some(
                  RecoveryResults.Record(
                    completion.hostCompletionId,
                    completion.successorSessionId,
                    expires,
                    rows.getBytes(2).toVector
                  )
                )
              }
            }
          }
        }
        .map(_.map(_.map(record => RecoveryResults.consumed(record, invocation))))
  def presentRecovery(
    record: RecoveryResults.Consumed,
    context: AuthenticatedActionContext[Principal]
  ): Future[UiOutcome[Page]] =
    Effect[Future].delay(recoveryResults.present(record, context))

  override def maintenance(): Future[Unit] =
    if (!reserve()) Future.unit
    else {
      val work =
        try
          Future {
            Using.resource(source.getConnection) { connection =>
              sql(connection, "DELETE FROM example_recovery_result WHERE expires_at<=?") { query =>
                query.setTimestamp(1, Timestamp.from(clock())); query.executeUpdate(); ()
              }
            }
          }(blockingContext).flatMap(_ => browser.maintenance())
        catch { case NonFatal(error) => Future.failed(error) }
      work.transform { result =>
        releaseJob(); result
      }(ExecutionContext.parasitic)
    }

  def increment(view: BrowserView[Principal]): Future[Either[TransactionFailure, Int]] = persistence.transact(view) {
    tx =>
      val subject = view.principal.map(_.value.subject).getOrElse(deny())
      val count = sql(
        tx.connection,
        "UPDATE example_counter SET value=value+1 WHERE subject=? AND value<2147483647 RETURNING value"
      ) { query =>
        query.setObject(1, subject)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next()) throw new OperationProtocolException(OperationError.CapacityExceeded)
          rows.getInt(1)
        }
      }
      audit(tx.connection, subject, "increment")
      count
  }
  def currentCount(principal: Principal): Future[Int] = Future {
    Using.resource(source.getConnection) { connection =>
      sql(connection, "SELECT value FROM example_counter WHERE subject=?") { query =>
        query.setObject(1, principal.subject)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next()) deny()
          rows.getInt(1)
        }
      }
    }
  }(blockingContext)
  def initialize(): Future[Unit] = Future {
    Using.resource(source.getConnection) { connection =>
      connection.setAutoCommit(false)
      try {
        persistence.initialize(connection)
        schema.foreach(text => Using.resource(connection.createStatement())(_.executeUpdate(text)))
        val password = DomainPolicy.passwordHash("password").toArray
        DomainPolicy.accounts.foreach { seeded =>
          sql(
            connection,
            "INSERT INTO example_account(id,name,version,enabled,password_hash,factor_hash) VALUES (?,?,1,TRUE,?,?) ON CONFLICT(id) DO NOTHING"
          ) { query =>
            query.setObject(1, seeded.id); query.setString(2, seeded.name); query.setBytes(3, password)
            query.setBytes(4, seeded.factor.map(value => DomainPolicy.factorHash(value).toArray).orNull);
            query.executeUpdate()
          }
          sql(connection, "INSERT INTO example_counter(subject,value) VALUES (?,0) ON CONFLICT(subject) DO NOTHING") {
            query =>
              query.setObject(1, seeded.id); query.executeUpdate()
          }
        }
        connection.commit()
      } catch { case error: Throwable => connection.rollback(); throw error }
    }
  }(blockingContext)
  override def close(): Future[Unit] = {
    stopped.set(true)
    if (jobs.get() == 0) drained.trySuccess(())
    drained.future.flatMap(_ => browser.close())(ExecutionContext.parasitic)
  }
  private val schema = Vector(
    """CREATE TABLE IF NOT EXISTS example_account(id UUID PRIMARY KEY,name TEXT UNIQUE NOT NULL,version BIGINT NOT NULL,
      enabled BOOLEAN NOT NULL,password_hash BYTEA NOT NULL,factor_hash BYTEA)""",
    """CREATE TABLE IF NOT EXISTS example_session(id UUID PRIMARY KEY,completion UUID UNIQUE NOT NULL,subject UUID NOT NULL REFERENCES example_account(id),
      version BIGINT NOT NULL,enabled BOOLEAN NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL,acknowledged BOOLEAN NOT NULL DEFAULT FALSE)""",
    """CREATE TABLE IF NOT EXISTS example_consumed_proof(ceremony UUID PRIMARY KEY,subject UUID NOT NULL REFERENCES example_account(id),challenge_reference TEXT UNIQUE)""",
    """CREATE TABLE IF NOT EXISTS example_counter(subject UUID PRIMARY KEY REFERENCES example_account(id),value INTEGER NOT NULL CHECK(value>=0))""",
    """CREATE TABLE IF NOT EXISTS example_recovery_code(subject UUID NOT NULL REFERENCES example_account(id),digest BYTEA NOT NULL,PRIMARY KEY(subject,digest))""",
    """CREATE TABLE IF NOT EXISTS example_recovery_result(completion UUID PRIMARY KEY,session UUID UNIQUE NOT NULL REFERENCES example_session(id),
      expires_at TIMESTAMP WITH TIME ZONE NOT NULL,encrypted BYTEA NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS example_audit(id UUID PRIMARY KEY,subject UUID NOT NULL REFERENCES example_account(id),kind TEXT NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL)"""
  )
}
