package spoonbill.auth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.Qsid
import spoonbill.security.Identifiers.{ConnectionId, ViewOwnerId, ViewSessionId}
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.security.ViewFence
import spoonbill.security.transaction.TransactionFailure
import spoonbill.server.BrowserSessionToken

enum BrowserFailure {
  case Denied, Missing, Expired, Used, Capacity, Closed, StorageFailure
  case Settlement(value: TransactionFailure)
}

/**
 * Transport-only capture. It is intentionally neither Product nor Serializable.
 */
final class BrowserIdentity[P] private[auth] (
  private[spoonbill] val issuer: AnyRef,
  private[spoonbill] val binding: String,
  private[spoonbill] val token: Option[String],
  val generation: Long,
  val host: Option[HostPrincipal[P]],
  val sessionId: Option[UUID]
) {
  def principal: Option[HostPrincipal[P]] = host
  lazy val authorityStamp: BrowserAuthorityStamp = BrowserAuthorityStamp(
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(binding.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .toVector,
    generation,
    sessionId,
    host.map(_.stamp)
  )
  def sameAuthority(other: BrowserIdentity[?]): Boolean =
    (issuer eq other.issuer) && binding == other.binding && token == other.token &&
      generation == other.generation && sessionId == other.sessionId && host.map(_.stamp) == other.host.map(_.stamp)
  override def toString: String = "BrowserIdentity(<redacted>)"
}

/** One physical owner. A reconnect must acquire a new view capability. */
final class BrowserView[P] private[auth] (
  private[spoonbill] val issuer: AnyRef,
  val identity: BrowserIdentity[P],
  val qsid: Qsid,
  val connectionId: ConnectionId,
  val ownerId: UUID,
  val epoch: Long
) {
  def principal: Option[HostPrincipal[P]] = identity.principal
  def fence: ViewFence = ViewFence(
    ViewSessionId.fromUuid(
      UUID.nameUUIDFromBytes(BrowserView.key(qsid).getBytes(java.nio.charset.StandardCharsets.UTF_8))
    ),
    ViewOwnerId.fromUuid(ownerId),
    ViewOwnershipEpoch.fromLong(epoch).toOption.get
  )
  override def toString: String = "BrowserView(<redacted>)"
}

object BrowserView {
  def key(qsid: Qsid): String = s"${qsid.deviceId.length}:${qsid.deviceId}${qsid.sessionId}"
}

/**
 * Provider-owned live ceremony, distinct from its browser-visible lookup ID.
 * Possession alone never authorizes preparation: providers recheck its durable
 * record, captured browser generation, physical owner, purpose and deadline.
 */
final class BrowserCeremony private[auth] (
  private[spoonbill] val issuer: AnyRef,
  private[spoonbill] val view: BrowserView[?],
  val id: CeremonyId,
  val purpose: String,
  val expiresAt: Instant,
  val challenge: Option[BrowserChallenge]
) {
  private[auth] val admitted = new AtomicBoolean()

  /** Non-bearer lookup key for application rate policy; never a login proof. */
  def browserKey: Vector[Byte]  = view.identity.authorityStamp.bindingKey
  override def toString: String = "BrowserCeremony(<redacted>)"
}

/**
 * Restricted successful settlement; contains no execution authority or token.
 */
final class CommittedAuthentication private[auth] (private[spoonbill] val issuer: AnyRef, val completionId: UUID) {
  override def toString: String = "CommittedAuthentication(<redacted>)"
}

enum BrowserRecovery {
  case Committed(receipt: CommittedAuthentication)
  case NotCommitted
}

/**
 * Returned only inside the host transaction. The credential never belongs in UI
 * state, a case class product, an action input or an application log.
 */
final class HostSessionPrepared(
  val sessionId: UUID,
  val completionId: UUID,
  val expiresAt: Instant,
  private[spoonbill] val credential: BrowserSessionToken
) {
  override def toString: String = "HostSessionPrepared(<redacted>)"
}

/**
 * SPI implemented by authority providers, not application action handlers. All
 * captures derive from transport cookies; browser lookup IDs never grant
 * authentication. Every returned principal is current at its transaction's
 * authorization boundary. Later protected mutations must reauthorize in their
 * own transaction. Implementations own bounded admission and settlement-aware
 * release; close must wait for their already admitted transactions.
 */
trait BrowserPersistence[F[_], P] {
  def scope: BrowserProviderScope
  def capabilities: BrowserProviderCapabilities
  def accepts(receipt: CommittedAuthentication): Boolean
  def capture(binding: String, token: Option[String], activate: Boolean): F[Either[BrowserFailure, BrowserIdentity[P]]]
  /** Register a server-generated HTTP bootstrap for first attachment on another
    * serving node. Unknown client view IDs must still be rejected by resume.
    */
  def registerView(identity: BrowserIdentity[P], qsid: Qsid)(using F: spoonbill.effect.Effect[F]): F[Either[BrowserFailure, Unit]] =
    F.pure(Left(BrowserFailure.Denied))
  def claim(
    identity: BrowserIdentity[P],
    qsid: Qsid,
    connectionId: ConnectionId,
    existingOnly: Boolean
  ): F[Either[BrowserFailure, BrowserView[P]]]
  def authorize(view: BrowserView[P]): F[Either[BrowserFailure, BrowserIdentity[P]]]
  /** Admit one already materialized immutable output at the ownership boundary.
    * A successful result applies only to this value, never to future rendering,
    * callbacks, streams or subsequent frames. Shared providers serialize this
    * check with takeover and expose success only after known settlement. Bytes
    * admitted before takeover may subsequently arrive over the network.
    */
  def admitOutput[A](view: BrowserView[P], output: A)(using F: spoonbill.effect.Effect[F]): F[Either[BrowserFailure, A]] =
    F.map(authorize(view))(_.map(_ => output))
  /** Admit a fixed output and a pure, bounded presentation predicate against
    * the same fresh authority observation. The predicate must not perform I/O,
    * retain authority or grant permission for a later output.
    */
  def admitOutputIf[A](view: BrowserView[P], output: A)(allowed: BrowserIdentity[P] => Boolean)(
    using F: spoonbill.effect.Effect[F]
  ): F[Either[BrowserFailure, A]] =
    F.map(authorize(view))(_.flatMap(identity => Either.cond(allowed(identity), output, BrowserFailure.Denied)))
  def release(view: BrowserView[P]): F[Unit]
  def begin(view: BrowserView[P], purpose: String): F[Either[BrowserFailure, BrowserCeremony]]
  def bindChallenge(ceremony: BrowserCeremony, challenge: BrowserChallenge): F[Either[BrowserFailure, BrowserCeremony]]
  def resume(view: BrowserView[P], id: CeremonyId, purpose: String): F[Either[BrowserFailure, BrowserCeremony]]
  def pending(view: BrowserView[P]): F[Either[BrowserFailure, Option[BrowserContinuation]]]
  def recover(view: BrowserView[P], id: CeremonyId): F[Either[BrowserFailure, BrowserRecovery]]
  def deliver(completionId: UUID, binding: String): F[Option[BrowserSessionToken]]
  def logout(binding: String, token: Option[String]): F[Either[BrowserFailure, Unit]]
  def maintenance(): F[Unit]
  def close(): F[Unit]
}

trait TransactionalBrowserPersistence[F[_], G[_], P] extends BrowserPersistence[F, P] {
  type Tx

  /**
   * Consume host proof, create the host session and write its audit on the same
   * transaction as browser preparation. Rejection must fail G/throw, never
   * return an ordinary Left that permits the enclosing transaction to commit.
   * Success is exposed only after acknowledged outermost settlement.
   */
  def prepare(ceremony: BrowserCeremony)(
    host: Tx => G[HostSessionPrepared]
  ): F[Either[TransactionFailure, CommittedAuthentication]]
}

/**
 * Capability issuer for trusted providers. Constructing another issuer cannot
 * forge capabilities accepted by an existing provider. Providers must still
 * check authoritative storage; these checks only establish local provenance.
 */
final class BrowserCapabilityScope[P] {
  private val owner                                 = new Object
  private val stopped                               = new AtomicBoolean()
  def isClosed: Boolean                             = stopped.get()
  def close(): Unit                                 = stopped.set(true)
  def owns(value: BrowserIdentity[?]): Boolean      = !isClosed && (value.issuer eq owner)
  def owns(value: BrowserView[?]): Boolean          = !isClosed && (value.issuer eq owner)
  def owns(value: BrowserCeremony): Boolean         = !isClosed && (value.issuer eq owner)
  def owns(value: CommittedAuthentication): Boolean = !isClosed && (value.issuer eq owner)
  def identity(
    binding: String,
    token: Option[String],
    generation: Long,
    host: Option[HostPrincipal[P]],
    sessionId: Option[UUID] = None
  ): BrowserIdentity[P] =
    new BrowserIdentity(owner, binding, token, generation, host, sessionId)
  def view(
    identity: BrowserIdentity[P],
    qsid: Qsid,
    connectionId: ConnectionId,
    ownerId: UUID,
    epoch: Long
  ): BrowserView[P] = {
    require(owns(identity), "Foreign browser identity")
    new BrowserView(owner, identity, qsid, connectionId, ownerId, epoch)
  }
  def ceremony(
    view: BrowserView[P],
    id: CeremonyId,
    purpose: String,
    expiresAt: Instant,
    challenge: Option[BrowserChallenge]
  ): BrowserCeremony = {
    require(owns(view), "Foreign browser view")
    new BrowserCeremony(owner, view, id, purpose, expiresAt, challenge)
  }
  def admit(value: BrowserCeremony): Boolean                 = owns(value) && value.admitted.compareAndSet(false, true)
  def committed(completionId: UUID): CommittedAuthentication = new CommittedAuthentication(owner, completionId)
}
