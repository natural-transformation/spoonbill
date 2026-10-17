package spoonbill.auth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.ViewFence
import spoonbill.security.transaction.TransactionFailure
import spoonbill.server.BrowserSessionToken

/**
 * A distinct provider-issued ceremony; ordinary ceremonies cannot substitute
 * for it.
 */
final class BrowserReplacementCeremony private[auth] (
  private[spoonbill] val issuer: AnyRef,
  private[spoonbill] val view: BrowserView[?],
  val id: CeremonyId,
  val purpose: String,
  val expiresAt: Instant,
  val challenge: Option[BrowserChallenge]
) {
  private[auth] val admitted    = new AtomicReference[Option[AnyRef]](None)
  def browserKey: Vector[Byte]  = view.identity.authorityStamp.bindingKey
  override def toString: String = "BrowserReplacementCeremony(<redacted>)"
}

/**
 * Framework-issued association with the preparation invocation, which may
 * differ from the invocation that began the ceremony. Retains neither the
 * action binding's sensitive scope nor a view containing transport credentials.
 */
final class BrowserReplacementInvocation private[auth] (
  private[spoonbill] val issuer: AnyRef,
  private[spoonbill] val invocationKey: AnyRef,
  val authority: BrowserAuthorityStamp,
  val fence: ViewFence,
  val connectionId: ConnectionId
) {

  /**
   * Local association only; a provider must still authorize its transaction.
   */
  def matchesView(view: BrowserView[?]): Boolean =
    (issuer eq view.issuer) && authority == view.identity.authorityStamp && fence == view.fence && connectionId == view.connectionId
  def sameInvocation(other: BrowserReplacementInvocation): Boolean =
    (issuer eq other.issuer) && (invocationKey eq other.invocationKey) &&
      authority == other.authority && fence == other.fence && connectionId == other.connectionId
  def belongsTo(context: spoonbill.action.AuthenticatedActionContext[?]): Boolean =
    (invocationKey eq context.invocationBinding.replacementIdentity) && connectionId == context.connectionId
  override def toString: String = "BrowserReplacementInvocation(<redacted>)"
}

object BrowserReplacementInvocation {

  /**
   * Capture a real framework invocation without requiring access to a
   * provider's private issuer object. The provider still checks issuance,
   * closure and fresh authoritative state before using this association.
   */
  private[spoonbill] def capture(
    view: BrowserView[?],
    binding: spoonbill.action.InvocationBinding
  ): BrowserReplacementInvocation = {
    require(
      view.identity.host.nonEmpty && view.identity.sessionId.nonEmpty &&
        view.connectionId == binding.connectionId,
      "Replacement invocation requires its authenticated view"
    )
    new BrowserReplacementInvocation(
      view.issuer,
      binding.replacementIdentity,
      view.identity.authorityStamp,
      view.fence,
      view.connectionId
    )
  }
}

/**
 * Known-committed settlement, usable only for its fixed invocation's
 * completion. Contains no credential, principal callback, application output or
 * BrowserView.
 */
final class CommittedReplacement private[auth] (
  private[spoonbill] val issuer: AnyRef,
  private[spoonbill] val invocationKey: AnyRef,
  val authority: BrowserAuthorityStamp,
  val fence: ViewFence,
  val connectionId: ConnectionId,
  val ceremonyId: CeremonyId,
  val completionId: UUID,
  /**
   * Original ceremony upper bound; stored delivery/activation may expire
   * sooner.
   */
  val expiresAt: Instant
) {
  private[auth] val completed   = new AtomicBoolean()
  override def toString: String = "CommittedReplacement(<redacted>)"
}

/** One immutable framework frame, never a general output-admission permit. */
final class BrowserReplacementControl private[auth] (private[spoonbill] val completionId: UUID) {
  override def toString: String = "BrowserReplacementControl(<redacted>)"
}

/**
 * Created by the HTTP boundary only after origin, input and cookie validation.
 * Raw cookie values stay transport-only. The identity is local to this request;
 * neither a caller-selected request ID nor a ceremony lookup is authority.
 */
final class BrowserReplacementRequest private[spoonbill] (
  val binding: String,
  val token: Option[String],
  val ceremonyId: Option[CeremonyId]
) {
  private[auth] val requestKey  = new Object
  override def toString: String = "BrowserReplacementRequest(<redacted>)"
}

/**
 * Request-bound delivery association, without a retained request or credential.
 */
final class BrowserReplacementDelivery private[auth] (
  private[spoonbill] val issuer: AnyRef,
  private[auth] val requestKey: AnyRef,
  val completionId: UUID
) {
  private[auth] val consumed    = new AtomicBoolean()
  override def toString: String = "BrowserReplacementDelivery(<redacted>)"
}

enum BrowserReplacementRecovery {

  /**
   * A valid successor cookie is already present. Attempt a fresh transport
   * handshake without spending delivery budget; this grants no principal.
   */
  case ActivateExistingCookie
  case Deliver(delivery: BrowserReplacementDelivery)
  case NotCommitted
  case CommittedUnavailable
}

/**
 * Verified metadata exposed inside a provider-owned transaction callback. These
 * IDs identify host records; retaining them grants no execution authority.
 */
final class VerifiedSuccessorCompletion private[auth] (
  val hostCompletionId: UUID,
  val successorSessionId: UUID
) {
  override def toString: String = "VerifiedSuccessorCompletion(<redacted>)"
}

/**
 * Optional replacement SPI. Providers lacking this extension do not support
 * replacement; ordinary preparation must never be used as a fallback.
 *
 * Implementations retain the inherited instance-dependent transaction type.
 * Public IDs and authority stamps are lookup metadata, never execution permits.
 * Provider capability factories still require authoritative storage validation;
 * local issuance alone cannot authorize delivery, activation or application
 * work. No existing provider advertises this extension until the complete
 * provider, runtime, transport and recovery path is implemented and validated.
 */
trait BrowserReplacementSupport[F[_], P] extends BrowserPersistence[F, P] {
  def beginReplacement(view: BrowserView[P], purpose: String): F[Either[BrowserFailure, BrowserReplacementCeremony]]
  def bindReplacementChallenge(
    ceremony: BrowserReplacementCeremony,
    challenge: BrowserChallenge
  ): F[Either[BrowserFailure, BrowserReplacementCeremony]]
  def resumeReplacement(
    view: BrowserView[P],
    id: CeremonyId,
    purpose: String
  ): F[Either[BrowserFailure, BrowserReplacementCeremony]]

  /**
   * Authoritatively recheck the exact invocation/owner, generation,
   * reservation, deadline and successor before admitting this single fixed
   * frame. Local issuer checks alone are insufficient; no ordinary predecessor
   * check applies.
   */
  def admitReplacementCompletion(
    handoff: CommittedReplacement,
    invocation: BrowserReplacementInvocation
  ): F[Either[BrowserFailure, BrowserReplacementControl]]

  /**
   * Validate storage without requiring an ordinary view. A valid existing
   * successor cookie takes precedence over delivery budget. Only a serialized
   * ceremony fence excluding its writer may establish NotCommitted; recovery
   * never invokes the preparation callback or repeats proof consumption.
   */
  def recoverReplacement(request: BrowserReplacementRequest): F[Either[BrowserFailure, BrowserReplacementRecovery]]

  /**
   * Read-only HTTP bootstrap eligibility. Never delivers credentials, grants
   * ordinary authority, or reconciles an unsettled attempt. A held valid
   * pending successor remains eligible after delivery budget is exhausted.
   */
  def replacementPending(request: BrowserReplacementRequest): F[Either[BrowserFailure, Boolean]]

  /**
   * Consume only a delivery associated with this exact HTTP request. Recheck
   * authoritative binding, generation, reservation, successor, deadline and
   * budget in the delivery transaction. Release the cookie only after known
   * settlement, exclusively through the framework's HttpOnly Set-Cookie path.
   */
  def deliverReplacement(
    request: BrowserReplacementRequest,
    delivery: BrowserReplacementDelivery
  ): F[Either[BrowserFailure, BrowserSessionToken]]
}

/**
 * Host mutation extension; transport discovery uses BrowserReplacementSupport
 * without erasing or guessing the provider's inner transaction effect.
 */
trait ReplacementBrowserPersistence[F[_], G[_], P]
    extends TransactionalBrowserPersistence[F, G, P]
    with BrowserReplacementSupport[F, P] {

  /**
   * Validate the captured predecessor in the mutation transaction; consume host
   * proof, replace host authority and reserve the successor atomically. Invoke
   * the callback at most once, close its handle, and issue the handoff only
   * after acknowledged outermost commit. A callback Left is not rollback.
   */
  def prepareReplacement(ceremony: BrowserReplacementCeremony, invocation: BrowserReplacementInvocation)(
    host: Tx => G[HostSessionPrepared]
  ): F[Either[TransactionFailure, CommittedReplacement]]

  /**
   * Resolve linkage from the exact current activated successor and authorize it
   * in the same transaction as the host callback. Invoke/evaluate the callback
   * at most once and expose its result only after known outermost settlement.
   * The metadata is not a permit for later work. Sensitive results must use the
   * invocation-owned one-shot disclosure holder, never retained plaintext.
   */
  def withSuccessorCompletion[A](view: BrowserView[P])(
    host: (Tx, VerifiedSuccessorCompletion) => G[A]
  ): F[Either[TransactionFailure, A]]
}
