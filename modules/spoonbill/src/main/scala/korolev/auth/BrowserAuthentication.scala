package spoonbill.auth

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.Qsid
import spoonbill.action.*
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.sensitive.SensitiveAccess
import spoonbill.server.*
import spoonbill.snapshot.ViewSnapshotSession
import spoonbill.state.{BoundedPresentationStore, StateStorage}
import spoonbill.web.Request

/**
 * Application functions operate on immutable ordinary presentation. Neither a
 * state field nor an opaque continuation lookup can establish authorization.
 * These functions must be pure, bounded and repeatable. In shared mode
 * `allowed` can run inside the authoritative output-admission transaction; it
 * must never perform I/O, block or mutate application state.
 */
final case class BrowserPresentation[S, P](
  fresh: () => S,
  allowed: (S, Option[P]) => Boolean,
  connected: (S, Option[P], Option[BrowserContinuation]) => S
)

/** Validates that a service still uses the exact coherent installation. */
trait BrowserSecurityInstallation[F[_], S] {
  private[spoonbill] def validate[M](config: SpoonbillServiceConfig[F, S, M]): Unit
}

/**
 * One local coordinator for a provider's fenced view ownership domain.
 * Password/factor policy and the atomic host transaction remain application
 * concerns. Construction performs no I/O or starts any background work. The
 * application owns close(), including on startup failure.
 */
final class BrowserAuthentication[F[_], S, P](
  val persistence: BrowserPersistence[F, P],
  presentation: BrowserPresentation[S, P],
  val configuration: BrowserConfiguration,
  storageLimits: BoundedPresentationStore.Limits = BoundedPresentationStore.Limits(),
  clock: () => Instant = () => Instant.now(),
  sensitiveAccess: BrowserView[P] => Option[SensitiveAccess[F, S]] = (_: BrowserView[P]) =>
    Option.empty[SensitiveAccess[F, S]],
  snapshots: BrowserView[P] => Option[ViewSnapshotSession[F, S]] = (_: BrowserView[P]) =>
    Option.empty[ViewSnapshotSession[F, S]],
  samePrincipal: (P, P) => Boolean = (left: P, right: P) => left == right
)(using F: Effect[F]) {
  require(configuration.errors.isEmpty, configuration.errors.mkString("; "))
  require(configuration.scope == persistence.scope, "Browser/provider scope mismatch")
  require(persistence.capabilities.atomicHostPreparation, "Provider cannot atomically prepare with the host store")
  private val shared = persistence.capabilities.ownership == BrowserOwnership.SharedViews
  private val replacementSupport = persistence match {
    case value: BrowserReplacementSupport[F, P] @unchecked => Some(value)
    case _                                                 => None
  }

  private val gate  = new BrowserCoordinatorGate[F](configuration.maxPendingOperations)
  private val store = new BoundedPresentationStore[F, S](storageLimits, clock)
  private final class Attached(val view: BrowserView[P]) {
    var lease    = Option.empty[store.Lease]
    var retiring = false
  }
  private var attached = Map.empty[ConnectionId, Attached]
  // Claims stay owned until acknowledged cleanup, including failed setup and
  // uncertain release. The bound is enforced before dispatching provider claim.
  private var owned = Map.empty[ConnectionId, Attached]
  // Non-secret stamps share the bounded view history's lifetime, never bearer tokens.
  private var previous = Map.empty[Qsid, (BrowserAuthorityStamp, spoonbill.security.ViewFence)]
  private val stopped  = new AtomicBoolean()

  private def denied[A]: F[A]                                           = F.fail(new SessionAccessDenied)
  private def requireResult[A](result: Either[BrowserFailure, A]): F[A] = result.fold(_ => denied, F.pure)
  private def principal(identity: BrowserIdentity[P]): Option[P]        = identity.principal.map(_.value)
  private def tokens(request: Request.Head): Option[(String, Option[String])] = {
    val names = Set(configuration.bindingCookieName, configuration.sessionCookieName)
    val pairs = Option(request.renderedCookie).toSeq.flatMap(_.split(';')).map(_.trim).filter(_.nonEmpty)
    val decoded =
      pairs.map(pair => scala.util.Try(java.net.URLDecoder.decode(pair.takeWhile(_ != '='), "UTF-8")).toOption)
    val duplicates = names.exists(name => decoded.count(_.contains(name)) > 1)
    val token      = request.cookie(configuration.sessionCookieName)
    if (duplicates || decoded.exists(_.isEmpty) || token.exists(!_.matches("[A-Za-z0-9_-]{43}"))) None
    else
      request
        .cookie(configuration.bindingCookieName)
        .filter(_.matches("[A-Za-z0-9_-]{43}"))
        .map(_ -> request.cookie(configuration.sessionCookieName))
  }
  private def capture(request: Request.Head, activate: Boolean): F[BrowserIdentity[P]] =
    tokens(request).fold(denied[BrowserIdentity[P]]) { case (binding, token) =>
      persistence.capture(binding, token, activate).flatMap(requireResult)
    }
  private def invalidate(value: Attached): F[Unit] = value.lease.fold(F.unit)(_.invalidate())
  private def detach(value: Attached, retain: Boolean = false): F[Unit] =
    if (!owned.get(value.view.connectionId).contains(value)) F.unit
    else {
      value.retiring = true
      if (attached.get(value.view.connectionId).contains(value)) attached -= value.view.connectionId
      // Fence local managers before waiting for SQL. If settlement fails, discard
      // retained presentation but keep the owned resource available for retry.
      val local = if (retain) value.lease.fold(F.unit)(_.release()) else invalidate(value)
      local
        .flatMap(_ => persistence.release(value.view))
        .map { _ =>
          if (owned.get(value.view.connectionId).contains(value)) owned -= value.view.connectionId
        }
        .recoverF { case error =>
          invalidate(value).recover { case _ => () }.flatMap(_ => F.fail(error))
        }
    }
  private def check(value: Attached): F[BrowserIdentity[P]] =
    persistence
      .authorize(value.view)
      .recoverF { case error =>
        detach(value).recover { case _ => () }.flatMap(_ => F.fail(error))
      }
      .flatMap {
        case Right(identity) if value.view.identity.sameAuthority(identity) => F.pure(identity)
        case _ =>
          detach(value).recover { case _ => () }.flatMap(_ => denied)
      }
  private def withView[A](id: ConnectionId)(run: Attached => F[A]): F[A] = gate {
    attached.get(id).fold(denied[A])(run)
  }

  val authority: SessionAuthority[F, P] = new SessionAuthority[F, P] {
    override private[spoonbill] val replacement = replacementSupport.map {
      _ => (binding: InvocationBinding, handoff: CommittedReplacement) =>
        gate {
          F.delay {
            val valid = attached.get(binding.connectionId).exists { actual =>
              !actual.retiring && (handoff.issuer eq actual.view.issuer) &&
              (handoff.invocationKey eq binding.replacementIdentity) &&
              handoff.connectionId == binding.connectionId && handoff.fence == actual.view.fence &&
              handoff.authority == actual.view.identity.authorityStamp && clock().isBefore(handoff.expiresAt)
            }
            if (valid) AccessDecision.Allowed else AccessDecision.Denied(AccessDenied.StaleAuthority)
          }
        }
    }
    def resolve(binding: InvocationBinding): F[Either[AccessDenied, P]] =
      withView(binding.connectionId)(check)
        .map(identity => principal(identity).toRight(AccessDenied.Unauthenticated))
        .recover { case _: SessionAccessDenied => Left(AccessDenied.StaleAuthority) }
    def revalidate(binding: InvocationBinding, captured: P): F[AccessDecision] = resolve(binding).map {
      case Right(current) if samePrincipal(current, captured) => AccessDecision.Allowed
      case _                                                  => AccessDecision.Denied(AccessDenied.StaleAuthority)
    }
  }

  private val access: SessionAccessControl[F, S] = new SessionAccessControl[F, S] {
    override private[spoonbill] def securityInstallation: Option[BrowserSecurityInstallation[F, S]] = Some(installation)
    def authorizeHttp(request: Request.Head, state: S): F[Unit] = gate {
      capture(request, activate = false).flatMap { identity =>
        if (presentation.allowed(state, principal(identity))) F.unit else denied
      }
    }
    override def prepareHttp(qsid: Qsid, request: Request.Head, state: S): Option[F[Unit]] =
      Option.when(shared)(gate {
        capture(request, activate = false).flatMap { identity =>
          if (!presentation.allowed(state, principal(identity))) denied
          else persistence.registerView(identity, qsid).flatMap(requireResult)
        }
      })
    def open(qsid: Qsid, request: Request.Head, owner: ConnectionId): F[SessionGuard[F, S]] =
      acquire(qsid, request, owner, existingOnly = false)
    override def resume(qsid: Qsid, request: Request.Head, owner: ConnectionId): Option[F[SessionGuard[F, S]]] =
      Some(acquire(qsid, request, owner, existingOnly = true))
  }

  private def acquire(
    qsid: Qsid,
    request: Request.Head,
    owner: ConnectionId,
    existingOnly: Boolean
  ): F[SessionGuard[F, S]] = gate {
    val origins = request.headers.collect { case (name, value) if name.equalsIgnoreCase("Origin") => value }
    if (origins.size != 1 || !configuration.allowedOrigins.contains(origins.head)) denied
    else if (owned.contains(owner) || owned.size.toLong >= storageLimits.active.toLong + storageLimits.pending.toLong)
      denied
    else
      capture(request, activate = true).flatMap { identity =>
        persistence.claim(identity, qsid, owner, existingOnly).flatMap(requireResult).flatMap { view =>
          val value = new Attached(view)
          owned += owner -> value
          val old     = attached.values.find(_.view.qsid == qsid)
          val prior   = previous.get(qsid)
          val changed = prior.exists(_._1 != identity.authorityStamp)
          // Same-identity takeover transfers the node map directly in claim; it
          // must not round-trip through the independently bounded disconnect cache.
          val retire = if (changed) old.fold(F.unit)(previous => detach(previous)) else F.unit
          val prepare = retire.flatMap { _ =>
            if (changed) store.invalidate(qsid, prior.get._2) else F.unit
          }.flatMap(_ => store.storage.exists(qsid.deviceId, qsid.sessionId)).flatMap { exists =>
            if (!exists || changed) {
              // A changed identity cannot inherit any previous presentation nodes.
              if (exists) denied[Boolean]
              else
                F.delay(presentation.fresh())
                  .flatMap(state => store.bootstrap(qsid, state, resetBrowser = true).map(_ => true))
            } else F.pure(false)
          }
          prepare
            .flatMap(reconstructed => store.claim(qsid, view.fence).map(lease => (reconstructed, lease)))
            .flatMap { case (reconstructed, lease) =>
              val applicationInitialized = new AtomicBoolean(!reconstructed)
              value.lease = Some(lease)
              old
                .fold(F.unit)(previous => detach(previous))
                .flatMap(_ => persistence.authorize(view))
                .flatMap(requireResult)
                .flatMap { current =>
                  if (!identity.sameAuthority(current)) denied[Unit] else lease.publish()
                }
                .map { _ =>
                  val guard = new SessionGuard[F, S] {
                    val applicationStateInitialization =
                      if (reconstructed)
                        ApplicationStateInitialization
                          .Reconstructed(() => F.delay { applicationInitialized.set(true); () })
                      else ApplicationStateInitialization.Retained[F]()
                    override private[spoonbill] val replacementCompletion = replacementSupport.map {
                      provider => (handoff: CommittedReplacement, binding: InvocationBinding) =>
                        withView(owner) { actual =>
                          if (binding.connectionId != owner || actual.retiring) denied[BrowserReplacementControl]
                          else
                            provider
                              .admitReplacementCompletion(
                                handoff,
                                BrowserReplacementInvocation.capture(actual.view, binding)
                              )
                              .flatMap(requireResult)
                              .map { control =>
                                actual.retiring = true
                                control
                              }
                        }
                    }
                    private val released                                         = new AtomicBoolean()
                    override val actionAuthority: Option[SessionAuthority[F, ?]] = Some(authority)
                    override val sensitive: Option[SensitiveAccess[F, S]] = sensitiveAccess(view).map { policy =>
                      new SensitiveAccess[F, S] {
                        def authorize(purpose: spoonbill.sensitive.Purpose, state: S): F[spoonbill.sensitive.Audience] =
                          withView(owner)(value => check(value).flatMap(_ => policy.authorize(purpose, state)))
                      }
                    }
                    override val viewSnapshots: Option[ViewSnapshotSession[F, S]] = snapshots(view)
                    override val ownershipEpoch: Option[spoonbill.security.Versions.ViewOwnershipEpoch] =
                      Option.when(shared || lease.resetBrowser)(view.fence.epoch)
                    override val outputAdmission: Option[SessionOutputAdmission[F, S]] =
                      Option.when(shared)(new SessionOutputAdmission[F, S] {
                        def apply[A](output: A, produced: S, current: S): F[A] = withView(owner) { actual =>
                          persistence
                            .admitOutputIf(actual.view, output) { identity =>
                              val who = principal(identity)
                              actual.view.identity.sameAuthority(identity) &&
                              presentation.allowed(produced, who) && presentation.allowed(current, who)
                            }
                            .flatMap(requireResult)
                            .recoverF { case error =>
                              detach(actual).recover { case _ => () }.flatMap(_ => F.fail(error))
                            }
                        }
                      })
                    def authorize(state: S): F[Unit] = withView(owner) { actual =>
                      check(actual).flatMap { fresh =>
                        if (presentation.allowed(state, principal(fresh))) F.unit
                        else {
                          detach(actual).flatMap(_ => denied)
                        }
                      }
                    }
                    def connected(state: S): F[S] = withView(owner) { actual =>
                      check(actual).flatMap { fresh =>
                        persistence.pending(view).flatMap(requireResult).flatMap { continuation =>
                          F.delay(presentation.connected(state, principal(fresh), continuation)).flatMap { result =>
                            if (presentation.allowed(result, principal(fresh))) F.pure(result) else denied
                          }
                        }
                      }
                    }
                    def close(): F[Unit] = F.delayAsync {
                      if (!released.compareAndSet(false, true) || stopped.get()) F.unit
                      else
                        gate.cleanup {
                          owned.get(owner) match {
                            case Some(current) if current eq value =>
                              detach(value, retain = !value.retiring && applicationInitialized.get())
                            case _ => F.unit
                          }
                        }.recoverF { case error => F.delay(released.set(false)).flatMap(_ => F.fail(error)) }
                    }
                  }
                  previous += qsid  -> (identity.authorityStamp -> view.fence)
                  attached += owner -> value
                  guard
                }
            }
            .recoverF { case error =>
              old
                .fold(F.unit)(previous => detach(previous).recover { case _ => () })
                .flatMap(_ => detach(value).recover { case _ => () })
                .flatMap(_ => F.fail(error))
            }
        }
      }
  }

  private val completion = AuthenticationCompletionConfig[F](
    configuration.bindingCookieName,
    configuration.sessionCookieName,
    configuration.allowedOrigins,
    configuration.secureCookies,
    configuration.cookieMaxAgeSeconds,
    (id, binding) => gate(persistence.deliver(id, binding)),
    (binding, token) =>
      gate {
        persistence.logout(binding, token).flatMap(requireResult).flatMap { _ =>
          val revoked = attached.values.filter(_.view.identity.binding == binding).toVector
          revoked.foldLeft(F.unit)((done, value) => done.flatMap(_ => detach(value)))
        }
      },
    logoutRedirect = Some(configuration.logoutRedirect),
    replacement = replacementSupport.map { provider =>
      AuthenticationReplacementConfig[F](
        request => gate(provider.replacementPending(request).flatMap(requireResult)),
        request =>
          gate {
            provider.recoverReplacement(request).flatMap {
              case Right(BrowserReplacementRecovery.ActivateExistingCookie) =>
                F.pure(AuthenticationReplacementRecovery.ActivateExistingCookie)
              case Right(BrowserReplacementRecovery.NotCommitted) =>
                F.pure(AuthenticationReplacementRecovery.NotCommitted)
              case Right(BrowserReplacementRecovery.Deliver(delivery)) =>
                provider.deliverReplacement(request, delivery).map {
                  case Right(token) => AuthenticationReplacementRecovery.Deliver(token)
                  case _            => AuthenticationReplacementRecovery.Unavailable
                }
              case _ => F.pure(AuthenticationReplacementRecovery.Unavailable)
            }
          },
        (binding, token) =>
          gate {
            if (token.isEmpty) F.pure(false)
            else persistence.capture(binding, token, activate = true).map(_.exists(_.host.nonEmpty))
          }
      )
    }
  )
  private val installation = new BrowserSecurityInstallation[F, S] {
    def validate[M](config: SpoonbillServiceConfig[F, S, M]): Unit = {
      require(config.sessionAccessControl.exists(_ eq access), "Browser access control was replaced")
      require(config.authenticationCompletion.exists(_ eq completion), "Browser completion configuration was replaced")
      require(config.stateStorage eq store.storage, "Browser presentation storage was replaced")
      require(
        config.webSocketEnabled && !config.webSocketCompressionEnabled,
        "Browser authentication requires uncompressed WebSocket"
      )
    }
  }
  def configure[M](base: SpoonbillServiceConfig[F, S, M]): SpoonbillServiceConfig[F, S, M] = {
    require(
      base.sessionAccessControl.isEmpty && base.authenticationCompletion.isEmpty && base.stateStorage == null &&
        base.browserSecurityInstallation.isEmpty,
      "Browser security must be installed once as one coherent bundle"
    )
    val result = base.copy(
      sessionAccessControl = Some(access),
      authenticationCompletion = Some(completion),
      stateStorage = store.storage,
      browserSecurityInstallation = Some(installation)
    )(base.executionContext)
    installation.validate(result)
    result
  }
  def begin(context: PublicActionContext, purpose: String = "sign-in"): F[Either[BrowserFailure, BrowserCeremony]] =
    withView(context.connectionId)(value => check(value).flatMap(_ => persistence.begin(value.view, purpose))).recover {
      case _: SessionAccessDenied => Left(BrowserFailure.Denied)
    }

  /**
   * Ordinary sign-in stays supported; authenticated security changes use
   * beginReplacement.
   */
  @deprecated("Use beginReplacement for authenticated security changes; begin is for ordinary sign-in", "5.1.0")
  def begin(context: AuthenticatedActionContext[P]): F[Either[BrowserFailure, BrowserCeremony]] =
    begin(context: PublicActionContext, "sign-in")

  /**
   * Ordinary sign-in stays supported; authenticated security changes use
   * beginReplacement.
   */
  @deprecated("Use beginReplacement for authenticated security changes; begin is for ordinary sign-in", "5.1.0")
  def begin(context: AuthenticatedActionContext[P], purpose: String): F[Either[BrowserFailure, BrowserCeremony]] =
    begin(context: PublicActionContext, purpose)

  def resume(
    context: PublicActionContext,
    id: CeremonyId,
    purpose: String = "sign-in"
  ): F[Either[BrowserFailure, BrowserCeremony]] =
    withView(context.connectionId)(value =>
      check(value).flatMap(_ => persistence.resume(value.view, id, purpose))
    ).recover { case _: SessionAccessDenied => Left(BrowserFailure.Denied) }

  /**
   * Resume a replacement through resumeReplacement instead of an ordinary
   * ceremony.
   */
  @deprecated("Use resumeReplacement for authenticated security changes; resume is for ordinary sign-in", "5.1.0")
  def resume(context: AuthenticatedActionContext[P], id: CeremonyId): F[Either[BrowserFailure, BrowserCeremony]] =
    resume(context: PublicActionContext, id, "sign-in")

  /**
   * Resume a replacement through resumeReplacement instead of an ordinary
   * ceremony.
   */
  @deprecated("Use resumeReplacement for authenticated security changes; resume is for ordinary sign-in", "5.1.0")
  def resume(
    context: AuthenticatedActionContext[P],
    id: CeremonyId,
    purpose: String
  ): F[Either[BrowserFailure, BrowserCeremony]] =
    resume(context: PublicActionContext, id, purpose)

  def recover(context: PublicActionContext, id: CeremonyId): F[Either[BrowserFailure, BrowserRecovery]] =
    withView(context.connectionId)(value => check(value).flatMap(_ => persistence.recover(value.view, id))).recover {
      case _: SessionAccessDenied => Left(BrowserFailure.Denied)
    }

  /**
   * Replacement reconciliation uses the fixed recovery outcome and transport
   * boundary.
   */
  @deprecated("Use recoverReplacementOutcome for authenticated replacement; recover is for ordinary sign-in", "5.1.0")
  def recover(context: AuthenticatedActionContext[P], id: CeremonyId): F[Either[BrowserFailure, BrowserRecovery]] =
    recover(context: PublicActionContext, id)

  /**
   * Resolve a server-owned view for a host transaction. The provider must
   * reauthorize it inside that transaction; this capture is not a write permit.
   */
  def currentView(context: PublicActionContext): F[Either[BrowserFailure, BrowserView[P]]] =
    withView(context.connectionId)(value => check(value).map(_ => Right(value.view))).recover {
      case _: SessionAccessDenied => Left(BrowserFailure.Denied)
    }

  /**
   * HTTP state loaders may derive display data from this current observation;
   * the mandatory HTTP guard still authorizes the resulting presentation.
   */
  def currentPrincipal(request: Request.Head): F[Either[BrowserFailure, Option[P]]] = gate {
    tokens(request) match {
      case None                   => F.pure(Left(BrowserFailure.Denied))
      case Some((binding, token)) => persistence.capture(binding, token, activate = false).map(_.map(principal))
    }
  }
  def complete(receipt: CommittedAuthentication): UiOutcome[S] = {
    require(persistence.accepts(receipt), "Completion receipt belongs to another or closed provider")
    UiOutcome.CommitAuthentication(receipt.completionId)
  }
  def beginReplacement(
    context: AuthenticatedActionContext[P],
    purpose: String
  ): F[Either[BrowserFailure, BrowserReplacementCeremony]] =
    replacementSupport.fold(F.pure[Either[BrowserFailure, BrowserReplacementCeremony]](Left(BrowserFailure.Denied))) {
      provider =>
        withView(context.connectionId)(value =>
          check(value).flatMap(_ => provider.beginReplacement(value.view, purpose))
        ).recover { case _: SessionAccessDenied => Left(BrowserFailure.Denied) }
    }
  def resumeReplacement(
    context: AuthenticatedActionContext[P],
    id: CeremonyId,
    purpose: String
  ): F[Either[BrowserFailure, BrowserReplacementCeremony]] =
    replacementSupport.fold(F.pure[Either[BrowserFailure, BrowserReplacementCeremony]](Left(BrowserFailure.Denied))) {
      provider =>
        withView(context.connectionId)(value =>
          check(value).flatMap(_ => provider.resumeReplacement(value.view, id, purpose))
        ).recover { case _: SessionAccessDenied => Left(BrowserFailure.Denied) }
    }

  /**
   * Capture the checked current invocation without marking replacement intent.
   * This association is not proof of an activated successor or permission to
   * disclose; withSuccessorCompletion must verify that inside its transaction.
   */
  def currentInvocation(
    context: AuthenticatedActionContext[P]
  ): F[Either[BrowserFailure, BrowserReplacementInvocation]] =
    if (replacementSupport.isEmpty) F.pure(Left(BrowserFailure.Denied))
    else
      withView(context.connectionId)(value =>
        check(value).map { _ =>
          Right(BrowserReplacementInvocation.capture(value.view, context.invocationBinding))
        }
      ).recover { case _: SessionAccessDenied => Left(BrowserFailure.Denied) }

  /**
   * Capture the invocation that intends to prepare a replacement and mark its
   * transport for retirement if that action's output is suppressed or fails.
   * Disclosure-only callers use currentInvocation instead.
   */
  def replacementInvocation(
    context: AuthenticatedActionContext[P]
  ): F[Either[BrowserFailure, BrowserReplacementInvocation]] =
    currentInvocation(context).map(_.map { invocation =>
      context.invocationBinding.replacementAttempted.set(true)
      invocation
    })
  def completeReplacement(handoff: CommittedReplacement): UiOutcome[S] = UiOutcome.CommitReplacement(handoff)
  def recoverReplacementOutcome(id: CeremonyId): UiOutcome[S] = {
    require(replacementSupport.nonEmpty, "Provider does not support replacement recovery")
    UiOutcome.RecoverReplacement(id)
  }
  def maintenance(): F[Unit] = gate {
    val retiring = owned.valuesIterator.filter(_.retiring).toVector
    retiring
      .foldLeft(F.unit)((done, value) => done.flatMap(_ => detach(value)))
      .flatMap(_ => persistence.maintenance())
      .flatMap(_ => store.resources.map(_ => ()))
  }
  def close(): F[Unit] = gate.close {
    stopped.set(true)
    val active         = owned.values.toVector
    var releaseFailure = Option.empty[Throwable]
    active
      .foldLeft(F.unit)((done, value) =>
        done.flatMap(_ =>
          detach(value).recover { case error =>
            if (releaseFailure.isEmpty) releaseFailure = Some(error)
          }
        )
      )
      .flatMap(_ => persistence.close())
      .flatMap(_ => store.close())
      .flatMap { _ =>
        attached = Map.empty; owned = Map.empty; previous = Map.empty
        releaseFailure.fold(F.unit)(F.fail)
      }
      .recoverF { case error => store.close().flatMap(_ => F.fail(error)) }
  }
}
