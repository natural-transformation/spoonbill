package spoonbill.auth

import example.browserauth.Principal
import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.FiniteDuration
import spoonbill.Qsid
import spoonbill.action.*
import spoonbill.effect.Effect
import spoonbill.internal.{Frontend, SensitiveDisclosureScope}
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}

/**
 * Builds a real invocation association for host-policy tests only. Cookie
 * delivery still goes through the application's HTTP completion endpoint.
 */
object MemoryReplacementTestAccess {
  def invocation[P](view: BrowserView[P]): BrowserReplacementInvocation =
    BrowserReplacementInvocation.capture(
      view,
      new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), view.connectionId)
    )

  def view(principal: Principal, session: UUID): BrowserView[Principal] = {
    val issuer = new BrowserCapabilityScope[Principal]
    val identity = issuer.identity(
      "b" * 43,
      Some("t" * 43),
      1L,
      Some(HostPrincipal(principal, principal.subject.toString, 2L, 2L, Instant.MAX)),
      Some(session)
    )
    issuer.view(
      identity,
      Qsid("test-device", UUID.randomUUID().toString),
      ConnectionId.fromUuid(UUID.randomUUID()),
      UUID.randomUUID(),
      1L
    )
  }

  final case class Bound(
    invocation: BrowserReplacementInvocation,
    context: AuthenticatedActionContext[Principal],
    close: () => Unit
  )

  /**
   * The dispatcher creates the real sealed authenticated context. Reusing
   * public IDs produces a different private invocation association.
   */
  def context(view: BrowserView[Principal], copiedIds: Option[AuthenticatedActionContext[Principal]] = None)(using
    ec: ExecutionContext,
    effect: Effect[Future]
  ): Future[Bound] = {
    val scope = new SensitiveDisclosureScope(new Frontend.RpcDeadlineScheduler {
      def schedule(delay: FiniteDuration)(task: () => Unit): () => Unit = () => ()
    })
    val binding = new InvocationBinding(
      copiedIds.fold(InvocationId.fromUuid(UUID.randomUUID()))(_.invocationId),
      view.connectionId,
      Some(scope)
    )
    val captured  = Promise[AuthenticatedActionContext[Principal]]()
    val principal = view.principal.get.value
    val authority = new SessionAuthority[Future, Principal] {
      def resolve(value: InvocationBinding): Future[Either[AccessDenied, Principal]] =
        Future.successful(Right(principal))
      def revalidate(value: InvocationBinding, current: Principal): Future[AccessDecision] =
        Future.successful(AccessDecision.Allowed)
    }
    val action = new Actions[Future, Unit, Principal].authenticated(
      ActionName.parse("test.capture-context").toOption.get,
      InputSchema.empty,
      ActionPolicy[Future, Unit, Principal]((_, _) => Future.successful(AccessDecision.Allowed))
    ) { (_, context) =>
      captured.success(context)
      Future.successful(UiOutcome.unchanged[Unit])
    }
    ActionDispatcher
      .authenticated(action, Vector.empty, binding, authority)
      .flatMap(_ => captured.future)
      .map(context => Bound(BrowserReplacementInvocation.capture(view, binding), context, () => scope.close()))
  }
}
