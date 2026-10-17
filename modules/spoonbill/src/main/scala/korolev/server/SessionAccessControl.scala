package spoonbill.server

import spoonbill.Qsid
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.web.Request

/**
 * Mandatory security integration, separate from optional observer extensions.
 * Implementations resolve cookies/server authority themselves. Presentation
 * state describes required access; it must never manufacture a principal. open
 * must release any partially acquired resources when it fails.
 */
trait SessionAccessControl[F[_], S] {
  private[spoonbill] def securityInstallation: Option[spoonbill.auth.BrowserSecurityInstallation[F, S]] = None
  def authorizeHttp(request: Request.Head, state: S): F[Unit]

  /**
   * Optional durable registration of the server-generated bootstrap before the
   * HTTP response can direct its first socket to another serving node.
   */
  def prepareHttp(qsid: Qsid, request: Request.Head, state: S): Option[F[Unit]] = None
  def open(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): F[SessionGuard[F, S]]

  /**
   * Opt-in restart recovery when there is no local bootstrap cache. The host
   * must validate current authority and atomically claim an existing view;
   * unknown view IDs must not allocate new ownership or snapshot rows. None
   * preserves the normal reload behavior for ephemeral views.
   */
  def resume(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): Option[F[SessionGuard[F, S]]] = None
}

/** Admission of a materialized immutable frame/chunk, not a reusable permit. */
trait SessionOutputAdmission[F[_], S] {

  /**
   * Validate both immutable presentations against the same current authority
   * that admits this output. No authorization may be cached across frames.
   */
  def apply[A](output: A, produced: S, current: S): F[A]
}

trait SessionGuard[F[_], S] {
  private[spoonbill] def replacementCompletion
    : Option[(spoonbill.auth.CommittedReplacement, spoonbill.action.InvocationBinding) => F[
      spoonbill.auth.BrowserReplacementControl
    ]] = None

  /**
   * Coordinator-installed action authority; foreign form authorities fail
   * closed.
   */
  def actionAuthority: Option[spoonbill.action.SessionAuthority[F, ?]] = None

  /** Separate, default-deny authority for transient sensitive disclosure. */
  def sensitive: Option[spoonbill.sensitive.SensitiveAccess[F, S]] = None

  /**
   * Opt-in, non-sensitive presentation persistence bound to this guard's
   * freshly resolved identity and exclusive view owner. Never derive this
   * binding from restored presentation or browser-supplied authority. Live
   * state uses a fresh in-memory manager. Any explicitly configured legacy
   * StateStorage is still used for HTTP bootstrap and must itself be transient.
   */
  def viewSnapshots: Option[spoonbill.snapshot.ViewSnapshotSession[F, S]] = None

  /**
   * Shared owners must fence every completed protected frame and download chunk
   * after materialization, immediately before transport handoff.
   */
  def outputAdmission: Option[SessionOutputAdmission[F, S]] = None

  /**
   * Rebuild the DOM on attachment even without a durable projection, for
   * example when restoring a server-side authentication challenge.
   */
  def ownershipEpoch: Option[spoonbill.security.Versions.ViewOwnershipEpoch] = None

  /**
   * Revalidate current session/ownership/policy; never await browser RPC here.
   */
  def authorize(state: S): F[Unit]

  /**
   * Derive connected presentation from freshly verified server authority.
   * Called after rebuilding the existing DOM baseline and before user events.
   */
  def connected(state: S): F[S]
  def close(): F[Unit]
}

final class SessionAccessDenied extends SecurityException("Session access denied")
