package spoonbill.security.jdbc

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.Connection
import spoonbill.auth.BrowserView
import spoonbill.effect.Effect
import spoonbill.security.Versions.{SecurityGeneration, SlotGeneration, ViewOwnershipEpoch, ViewRevision}
import spoonbill.security.transaction.{OperationError, TransactionFailure}
import spoonbill.snapshot.*

/** Ordinary coordinator integration. All projection operations join the
  * provider's bounded transaction admission and captured-authority check.
  * Only authenticated views have a durable projection; anonymous forms must
  * deliberately use a separate application-owned draft mechanism.
  */
private[jdbc] object JdbcBrowserSnapshots {
  def projected[F[_], P, S, D](
    provider: JdbcBrowserPersistence[F, P],
    format: SnapshotFormat[D],
    project: S => D,
    restore: (S, D) => S
  )(using F: Effect[F]): BrowserView[P] => Option[ViewSnapshotSession[F, S]] = view =>
    store(provider, view, format).map(ViewSnapshotSession.projected(_, project, restore))

  /** The same store, behind a session whose queued top-level write can skip an unchanged projection. */
  def projectedIfChanged[F[_], P, S, D](
    provider: JdbcBrowserPersistence[F, P],
    format: SnapshotFormat[D],
    project: S => D,
    restore: (S, D) => S
  )(using F: Effect[F]): BrowserView[P] => Option[ViewSnapshotSession[F, S]] = view =>
    store(provider, view, format).map(new JdbcObservedSnapshotSession(_, view, project, restore))

  private def store[F[_], P, D](
    provider: JdbcBrowserPersistence[F, P],
    view: BrowserView[P],
    format: SnapshotFormat[D]
  )(using F: Effect[F]): Option[JdbcInlineSnapshotStore[F, P, D]] =
    view.principal.map { principal =>
      // Subject keys accept printable ASCII; a digest preserves the broader
      // host subject contract without writing identifiable subject text.
      val subject = digest(principal.subject).bytes.iterator.map(value => f"${value & 0xff}%02x").mkString
      val identity = SnapshotIdentity(
        SnapshotKeys.Subject.parse(subject).toOption.get,
        SnapshotKeys.Scope.parse(s"policy:${principal.policyVersion}").toOption.get,
        view.identity.sessionId.getOrElse(throw new IllegalArgumentException("Authenticated view requires a session")),
        SecurityGeneration.fromLong(principal.securityVersion).toOption.get,
        SlotGeneration.fromLong(view.identity.generation).toOption.get
      )
      val data = new JdbcViewSnapshotData(
        provider.realm,
        provider.namespace,
        spoonbill.auth.BrowserView.key(view.qsid),
        digest(view.identity.binding),
        view.ownerId,
        view.fence.epoch,
        identity,
        format
      )
      new JdbcInlineSnapshotStore(provider, view, data)
    }

  private def digest(value: String): Digest256 =
    Digest256.fromBytes(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8))).toOption.get
}

/**
 * The inline projection store of one authenticated view. Every operation is one
 * provider transaction that freshly authorizes the view under its owner fence.
 * The encoded write is shared by the ordinary save and the observed session, so
 * a changed candidate is projected and encoded once.
 */
private[jdbc] final class JdbcInlineSnapshotStore[F[_], P, D](
  provider: JdbcBrowserPersistence[F, P],
  view: BrowserView[P],
  data: JdbcViewSnapshotData[D]
)(using F: Effect[F]) extends ViewSnapshotStore[F, D] {
  val ownerEpoch: ViewOwnershipEpoch = view.fence.epoch

  private def run[A](body: (Connection, ViewRevision) => Either[ViewSnapshotError, A]): F[Either[ViewSnapshotError, A]] =
    F.map(provider.transact(view) { tx =>
      val connection = tx.connection
      data.revision(connection).flatMap(revision => body(connection, revision))
    }) {
      case Right(result) => result
      case Left(TransactionFailure.Rejected(OperationError.HostDenied | OperationError.ForeignPermit)) =>
        Left(ViewSnapshotError.AccessDenied)
      case Left(TransactionFailure.Rejected(OperationError.ScopeClosed)) => Left(ViewSnapshotError.StaleOwner)
      case Left(_) => Left(ViewSnapshotError.StorageFailure)
    }

  def load(): F[Either[ViewSnapshotError, SnapshotLoad[D]]] = run(data.load)
  def save(expected: ViewRevision, value: D): F[Either[ViewSnapshotError, ViewRevision]] = write(expected, value, false)
  def reset(expected: ViewRevision, value: D): F[Either[ViewSnapshotError, ViewRevision]] = write(expected, value, true)

  /** The bytes a write of value stores, with the format's own encoder and limits. */
  def encode(value: D): Either[ViewSnapshotError, Array[Byte]] = data.encode(value)

  /** The ordinary fenced write of already encoded bytes with the expected revision. */
  def writeEncoded(expected: ViewRevision, payload: Array[Byte], resetting: Boolean): F[Either[ViewSnapshotError, ViewRevision]] =
    run((connection, revision) => data.write(connection, revision, expected, payload, resetting))

  private def write(expected: ViewRevision, value: D, resetting: Boolean): F[Either[ViewSnapshotError, ViewRevision]] =
    F.flatMap(F.delay(data.encode(value))) {
      case Left(error)    => F.pure(Left(error))
      case Right(payload) => writeEncoded(expected, payload, resetting)
    }
}
