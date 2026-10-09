package spoonbill.action

import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}

final class ActionName private (val value: String):
  override def toString: String = value

object ActionName:
  def parse(value: String): Either[InputConfigurationError, ActionName] =
    if value.matches("[a-z][a-z0-9_.-]{0,95}") then Right(new ActionName(value))
    else Left(InputConfigurationError.InvalidName)

/**
 * Issued by the host after binding an invocation to a real connection. IDs
 * identify a lookup; neither is authentication evidence by itself.
 */
final class InvocationBinding private[spoonbill] (
  val invocationId: InvocationId,
  val connectionId: ConnectionId,
  private[spoonbill] val sensitiveScope: Option[spoonbill.internal.SensitiveDisclosureScope] = None,
  private[spoonbill] val admission: InvocationAdmission = InvocationAdmission.untracked
):
  private[spoonbill] val sensitiveOwner = new spoonbill.sensitive.SensitiveDisclosure.Owner

enum AccessDenied:
  case Unauthenticated, Forbidden, StepUpRequired, StaleAuthority

enum AccessDecision:
  case Allowed
  case Denied(reason: AccessDenied)

/**
 * Trusted host port. P is a server-resolved actor/context, never browser
 * claims. revalidate must reject changed session, generation, subject or scope.
 * It must not merely compare user IDs. These checks do not fence business
 * transactions.
 */
trait SessionAuthority[F[_], P]:
  def resolve(binding: InvocationBinding): F[Either[AccessDenied, P]]
  def revalidate(binding: InvocationBinding, principal: P): F[AccessDecision]

/** Policies are read-only checks: do not consume operation grants here. */
final class PublicPolicy[F[_], I] private (val evaluate: I => F[AccessDecision])

object PublicPolicy:
  def apply[F[_], I](evaluate: I => F[AccessDecision]): PublicPolicy[F, I] = new PublicPolicy(evaluate)
  def allow[F[_]: Effect, I]: PublicPolicy[F, I]                           = apply(_ => Effect[F].pure(AccessDecision.Allowed))

final class ActionPolicy[F[_], I, P] private (val evaluate: (I, P) => F[AccessDecision])

object ActionPolicy:
  def apply[F[_], I, P](evaluate: (I, P) => F[AccessDecision]): ActionPolicy[F, I, P] =
    new ActionPolicy(evaluate)

sealed trait PublicActionContext:
  def invocationId: InvocationId
  def connectionId: ConnectionId
  /** A bounded connection-owned handoff, not permission to disclose. The
    * dedicated sensitive policy still authorizes the payload at delivery.
    * onResult must capture non-sensitive metadata only.
    */
  def presentSensitive[S](region: spoonbill.sensitive.RegionId, purpose: spoonbill.sensitive.Purpose,
    payload: spoonbill.sensitive.SensitivePayload, lifetime: scala.concurrent.duration.FiniteDuration)(
    onResult: spoonbill.sensitive.DisclosureOutcome => StateUpdate[S]
  ): UiOutcome[S]

sealed trait AuthenticatedActionContext[P] extends PublicActionContext:
  def principal: P

/**
 * Pure update descriptions. Callbacks are trusted application code; purity is a
 * contract, not a Scala guarantee. Only the owning view applies these updates.
 */
final class StateUpdate[S] private (private val reduce: S => S):
  def apply(state: S): S                            = reduce(state)
  def andThen(next: StateUpdate[S]): StateUpdate[S] = StateUpdate(state => next(reduce(state)))
  def zoom[Parent](read: Parent => S)(write: (Parent, S) => Parent): StateUpdate[Parent] =
    StateUpdate(parent => write(parent, reduce(read(parent))))

object StateUpdate:
  def apply[S](reduce: S => S): StateUpdate[S] = new StateUpdate(reduce)
  def unchanged[S]: StateUpdate[S]             = new StateUpdate(identity)

enum UiOutcome[S]:
  case Updated(update: StateUpdate[S])
  case Invalid(error: InputError)
  /** Opaque delivery handle; never a session credential or authentication proof. */
  case CommitAuthentication(completionId: java.util.UUID)
  /** One-shot handoff; cached results retain no payload after transfer. */
  case PresentSensitive(region: spoonbill.sensitive.RegionId, purpose: spoonbill.sensitive.Purpose,
    disclosure: spoonbill.sensitive.SensitiveDisclosure, lifetime: scala.concurrent.duration.FiniteDuration,
    onResult: spoonbill.sensitive.DisclosureOutcome => StateUpdate[S])

object UiOutcome:
  def update[S](reduce: S => S): UiOutcome[S] = Updated(StateUpdate(reduce))
  def unchanged[S]: UiOutcome[S]              = Updated(StateUpdate.unchanged)

final case class ActionDescriptor(name: ActionName, fields: Vector[InputField])

sealed trait Action[F[_], S, I]:
  def name: ActionName
  def input: InputSchema[I]
  final def descriptor: ActionDescriptor = ActionDescriptor(name, input.fields)

final class PublicAction[F[_], S, I] private[action] (
  val name: ActionName,
  val input: InputSchema[I],
  val policy: PublicPolicy[F, I],
  private[action] val run: (I, PublicActionContext) => F[UiOutcome[S]]
) extends Action[F, S, I]

final class AuthenticatedAction[F[_], S, I, P] private[action] (
  val name: ActionName,
  val input: InputSchema[I],
  val policy: ActionPolicy[F, I, P],
  private[action] val run: (I, AuthenticatedActionContext[P]) => F[UiOutcome[S]]
) extends Action[F, S, I]

final class Actions[F[_], S, P]:
  def public[I](name: ActionName, input: InputSchema[I], policy: PublicPolicy[F, I])(
    run: (I, PublicActionContext) => F[UiOutcome[S]]
  ): PublicAction[F, S, I] = new PublicAction(name, input, policy, run)

  def authenticated[I](name: ActionName, input: InputSchema[I], policy: ActionPolicy[F, I, P])(
    run: (I, AuthenticatedActionContext[P]) => F[UiOutcome[S]]
  ): AuthenticatedAction[F, S, I, P] = new AuthenticatedAction(name, input, policy, run)

enum ActionRejection:
  case Input(error: InputError)
  case Access(reason: AccessDenied)

enum InvocationResult[S]:
  /** The handler was not started. */
  case Rejected(reason: ActionRejection)
  /** The browser invocation became obsolete before its handler started. No UI
    * update or rejection callback is applied and the action is never replayed.
    */
  case Superseded()
  case Completed(outcome: UiOutcome[S])

  /**
   * The handler ran; its external effects may have committed. No UI output is
   * released.
   */
  case OutputSuppressed(reason: AccessDenied)

/**
 * Typed dispatch foundation, not a transport or view execution owner. The host
 * must enforce origin/binding checks, raw-frame limits, queue ownership and
 * serialization. A view must recheck its ownership/generation when applying the
 * returned pure update, because authority can change after this method returns.
 */
object ActionDispatcher:
  private abstract class BoundContext(binding: InvocationBinding) extends PublicActionContext:
    def invocationId: InvocationId = binding.invocationId
    def connectionId: ConnectionId = binding.connectionId
    def presentSensitive[S](region: spoonbill.sensitive.RegionId, purpose: spoonbill.sensitive.Purpose,
      payload: spoonbill.sensitive.SensitivePayload, lifetime: scala.concurrent.duration.FiniteDuration)(
      onResult: spoonbill.sensitive.DisclosureOutcome => StateUpdate[S]
    ): UiOutcome[S] =
      val scope = binding.sensitiveScope.getOrElse(throw new spoonbill.server.SessionAccessDenied)
      UiOutcome.PresentSensitive(region, purpose, scope.create(payload, binding.sensitiveOwner), lifetime, onResult)

  private final class PublicContext(binding: InvocationBinding) extends BoundContext(binding)
  private final class AuthenticatedContext[P](binding: InvocationBinding, val principal: P)
      extends BoundContext(binding) with AuthenticatedActionContext[P]

  private def discard[S](outcome: UiOutcome[S]): Unit = outcome match
    case UiOutcome.PresentSensitive(_, _, disclosure, _, _) => disclosure.discard()
    case _ => ()

  private def checkedOutcome[F[_]: Effect, S](outcome: UiOutcome[S], binding: InvocationBinding,
    check: => F[AccessDecision]): F[InvocationResult[S]] =
    val F = Effect[F]
    val foreign = outcome match
      case UiOutcome.PresentSensitive(_, _, disclosure, _, _) => !disclosure.belongsTo(binding.sensitiveOwner)
      case _ => false
    if foreign then F.pure(InvocationResult.OutputSuppressed(AccessDenied.StaleAuthority))
    else F.recoverF(F.flatMap(F.delayAsync(check)) {
      case AccessDecision.Allowed => F.pure[InvocationResult[S]](InvocationResult.Completed(outcome))
      case AccessDecision.Denied(reason) => F.delay[InvocationResult[S]] {
        discard(outcome)
        InvocationResult.OutputSuppressed(reason)
      }
    }) { case error => F.flatMap(F.delay(discard(outcome)))(_ => F.fail[InvocationResult[S]](error)) }

  private def admit[F[_]: Effect, S](binding: InvocationBinding)(
    operation: => F[InvocationResult[S]]
  ): F[InvocationResult[S]] =
    binding.admission.invoke[F, InvocationResult[S]](InvocationResult.Superseded())(operation)

  def public[F[_]: Effect, S, I](
    action: PublicAction[F, S, I],
    submitted: Vector[(String, String)],
    binding: InvocationBinding,
    limits: InputLimits = InputLimits.default
  ): F[InvocationResult[S]] =
    val F = Effect[F]
    F.flatMap(F.delay(action.input.decode(submitted, limits))) { decoded => admit(binding) {
      decoded match
        case Left(error) => F.pure(InvocationResult.Rejected(ActionRejection.Input(error)))
        case Right(input) =>
          F.flatMap(F.delayAsync(action.policy.evaluate(input))) { decision => admit(binding) {
            decision match
              case AccessDecision.Denied(reason) => F.pure(InvocationResult.Rejected(ActionRejection.Access(reason)))
              case AccessDecision.Allowed =>
                // admit's thunk already catches synchronous throws. Construct the
                // application effect here, without another suspension after it.
                F.flatMap(action.run(input, new PublicContext(binding))) { outcome =>
                  checkedOutcome(outcome, binding, action.policy.evaluate(input))
                }
          }}
    }}

  def authenticated[F[_]: Effect, S, I, P](
    action: AuthenticatedAction[F, S, I, P],
    submitted: Vector[(String, String)],
    binding: InvocationBinding,
    authority: SessionAuthority[F, P],
    limits: InputLimits = InputLimits.default
  ): F[InvocationResult[S]] =
    val F = Effect[F]
    F.flatMap(F.delay(action.input.decode(submitted, limits))) { decoded => admit(binding) {
      decoded match
        case Left(error) => F.pure(InvocationResult.Rejected(ActionRejection.Input(error)))
        case Right(input) =>
          F.flatMap(F.delayAsync(authority.resolve(binding))) { resolved => admit(binding) {
            resolved match
              case Left(reason) => F.pure(InvocationResult.Rejected(ActionRejection.Access(reason)))
              case Right(principal) =>
                def check: F[AccessDecision] = F.flatMap(F.delayAsync(action.policy.evaluate(input, principal))) {
                  case AccessDecision.Allowed => F.delayAsync(authority.revalidate(binding, principal))
                  case denied                 => F.pure(denied)
                }
                F.flatMap(check) { decision => admit(binding) {
                  decision match
                    case AccessDecision.Denied(reason) => F.pure(InvocationResult.Rejected(ActionRejection.Access(reason)))
                    case AccessDecision.Allowed =>
                      F.flatMap(action.run(input, new AuthenticatedContext(binding, principal))) { outcome =>
                        checkedOutcome(outcome, binding, check)
                      }
                }}
          }}
    }}
