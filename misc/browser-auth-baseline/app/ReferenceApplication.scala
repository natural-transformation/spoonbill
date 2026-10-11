package spoonbill.browserauthbaseline

import MemoryBrowserAuth.*
import avocet.Document
import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import spoonbill.{ActionForms, Context, Router}
import spoonbill.action.*
import spoonbill.security.transaction.TransactionFailure
import spoonbill.server.{SessionAccessDenied, SpoonbillServiceConfig, StateLoader}
import spoonbill.web.PathAndQuery.{/, Root}

object ReferenceApplication {

  /** Display data only. Credentials and server authority never enter Page. */
  final case class Page(
    protectedPage: Boolean = false,
    account: Option[String] = None,
    ceremony: Option[UUID] = None,
    challenge: Option[UUID] = None,
    recoveryPending: Boolean = false,
    counter: Int = 0,
    mutationUnresolved: Boolean = false,
    message: String = "Choose Begin sign-in to start."
  )
  final case class PasswordInput(ceremony: UUID, username: String, password: Secret)
  final case class FactorInput(ceremony: UUID, challenge: UUID, factor: Secret)

  def connected(page: Page, name: Option[String]): Page = page.copy(
    account = name,
    ceremony = if (name.nonEmpty) None else page.ceremony,
    challenge = if (name.nonEmpty) None else page.challenge,
    recoveryPending = if (name.nonEmpty) false else page.recoveryPending,
    message = name.fold(page.message)(value => s"Signed in as $value.")
  )
}

/**
 * Counted v3 integration glue, using the existing typed Scala action protocol.
 */
class ReferenceApplication[P](val backend: ReferenceBackend[P], val origin: String)(using ec: ExecutionContext) {
  import ReferenceApplication.*
  // SessionGuard.sensitive remains None: authentication does not opt into disclosure.
  val context = Context[Future, Page, Any]
  private type Binding = Context.Binding[Future, Page, Any]
  private val actions                               = new Actions[Future, Page, P]
  private def actionName(value: String): ActionName = ActionName.parse(value).toOption.get
  private def field(value: String): FieldName       = FieldName.parse(value).toOption.get
  private val invalidIdentifier                     = ValidationCode.parse("invalid-identifier").toOption.get
  private def identifier(name: String): InputSchema[UUID] = InputSchema
    .text(field(name), 36)
    .validate(invalidIdentifier)(value => value.length == 36 && Try(UUID.fromString(value)).isSuccess)
    .map(UUID.fromString)
  private val passwordInput = identifier("ceremony")
    .zip(InputSchema.text(field("username"), 64))
    .zip(InputSchema.secret(field("password"), 256))
    .map { case ((ceremony, username), password) => PasswordInput(ceremony, username, password) }
  private val factorInput = identifier("ceremony")
    .zip(identifier("challenge"))
    .zip(InputSchema.secret(field("factor"), 6))
    .map { case ((ceremony, challenge), factor) => FactorInput(ceremony, challenge, factor) }
  private val onRejected: ActionRejection => Page => Page = _ =>
    page =>
      page.copy(
        ceremony = None,
        challenge = None,
        recoveryPending = false,
        message = "This request was rejected. Begin a new sign-in if needed."
      )

  private def rejected(error: Failure): UiOutcome[Page] = UiOutcome.update(
    _.copy(
      ceremony = None,
      challenge = None,
      recoveryPending = false,
      message = error match {
        case Failure.Throttled => "Too many attempts. Wait before beginning another sign-in."
        case Failure.Capacity  => "The reference host has reached its capacity. No new attempt was admitted."
        case Failure.Expired   => "This sign-in expired. Begin a new sign-in."
        case _                 => "Sign-in was not accepted. Begin a new sign-in."
      }
    )
  )
  private def completion(ceremony: UUID, result: Either[Failure, Reply]): UiOutcome[Page] = result match {
    case Right(Reply.Prepared(id)) => UiOutcome.CommitAuthentication(id)
    case Right(Reply.Challenge(original, challenge)) =>
      UiOutcome.update(
        _.copy(
          ceremony = Some(original),
          challenge = Some(challenge),
          message = "Enter the factor for this original sign-in."
        )
      )
    case Left(Failure.Settlement(TransactionFailure.CommitUnknown)) =>
      UiOutcome.update(
        _.copy(
          ceremony = Some(ceremony),
          recoveryPending = true,
          message = "Commit acknowledgment was lost. Recover this attempt without resubmitting credentials."
        )
      )
    case Left(Failure.Used) =>
      UiOutcome.update(
        _.copy(
          ceremony = Some(ceremony),
          challenge = None,
          recoveryPending = true,
          message = "This attempt was already admitted. Recover its outcome without resubmitting credentials."
        )
      )
    case Left(error) => rejected(error)
  }

  val beginAction = actions.public(actionName("reference.begin"), InputSchema.empty, PublicPolicy.allow[Future, Unit]) {
    (_, ctx) =>
      backend.begin(ctx.connectionId).map {
        case Right(id) =>
          UiOutcome.update[Page](
            _.copy(
              ceremony = Some(id),
              challenge = None,
              recoveryPending = false,
              message = "Sign-in started. Enter the synthetic account credentials."
            )
          )
        case Left(error) => rejected(error)
      }
  }
  val passwordAction =
    actions.public(actionName("reference.password"), passwordInput, PublicPolicy.allow[Future, PasswordInput]) {
      (input, ctx) =>
        val owner    = ctx.connectionId
        val ceremony = input.ceremony
        val username = input.username
        input.password
          .withValue(value => backend.password(owner, ceremony, username, value))
          .map(completion(ceremony, _))
    }
  val factorAction =
    actions.public(actionName("reference.factor"), factorInput, PublicPolicy.allow[Future, FactorInput]) {
      (input, ctx) =>
        val owner     = ctx.connectionId
        val ceremony  = input.ceremony
        val challenge = input.challenge
        input.factor
          .withValue(value => backend.factor(owner, ceremony, challenge, value))
          .map(completion(ceremony, _))
    }
  val recoverAction =
    actions.public(actionName("reference.recover"), identifier("ceremony"), PublicPolicy.allow[Future, UUID]) {
      (ceremony, ctx) =>
        backend.recover(ctx.connectionId, ceremony).map {
          case Right(Recovery.Committed(id)) => UiOutcome.CommitAuthentication(id)
          case Right(Recovery.NotCommitted) =>
            UiOutcome.update[Page](
              _.copy(
                ceremony = None,
                challenge = None,
                recoveryPending = false,
                message = "The attempt did not commit. Begin a new sign-in."
              )
            )
          case Left(Failure.Settlement(TransactionFailure.CommitUnknown)) =>
            UiOutcome.update[Page](
              _.copy(
                ceremony = Some(ceremony),
                recoveryPending = true,
                message = "Disposition remains unknown. Recover the same attempt again."
              )
            )
          case Left(error) => rejected(error)
        }
    }
  val incrementAction = actions.authenticated(
    actionName("reference.increment"),
    InputSchema.empty,
    ActionPolicy[Future, Unit, P]((_, _) => Future.successful(AccessDecision.Allowed))
  ) { (_, ctx) =>
    backend.actionAuthority(ctx.connectionId, ctx.principal).flatMap {
      case Left(_) => Future.successful(UiOutcome.update[Page](_.copy(message = "The protected action was denied.")))
      case Right(authority) =>
        backend.protectedAction(ctx.connectionId, ctx.principal, authority).map {
          case Right(count) =>
            UiOutcome.update[Page](_.copy(counter = count, message = "Protected increment committed."))
          case Left(TransactionFailure.CommitUnknown) =>
            UiOutcome.update[Page](
              _.copy(mutationUnresolved = true, message = "The increment's disposition is unknown. Do not repeat it.")
            )
          case Left(_) => UiOutcome.update[Page](_.copy(message = "The protected increment did not complete."))
        }
    }
  }

  private def hidden(name: String, value: UUID): Document.Node[Binding] =
    input(`type` := "hidden", avocet.dsl.html.name := name, avocet.dsl.html.value := value.toString)
  private def beginForm: Document.Node[Binding] =
    form(ActionForms.onSubmit[Future, Page, Any, Unit](beginAction, onRejected), button("Begin sign-in"))
  private def signIn(page: Page): Document.Node[Binding] = div(
    p("Synthetic accounts: alice / password, or bob / password followed by factor 123456."),
    if (page.recoveryPending) Seq.empty[Document.Node[Binding]]
    else
      page.ceremony.toSeq.map(id =>
        page.challenge match {
          case Some(challenge) =>
            form(
              method := "post",
              action := "/reference-submit-unavailable",
              ActionForms.onSubmit[Future, Page, Any, FactorInput](factorAction, onRejected),
              hidden("ceremony", id),
              hidden("challenge", challenge),
              label("Factor", input(avocet.dsl.html.name := "factor", `type` := "password")),
              button("Verify factor")
            )
          case None =>
            form(
              method := "post",
              action := "/reference-submit-unavailable",
              ActionForms.onSubmit[Future, Page, Any, PasswordInput](passwordAction, onRejected),
              hidden("ceremony", id),
              label("Account", input(avocet.dsl.html.name := "username", `type` := "text")),
              label("Password", input(avocet.dsl.html.name := "password", `type` := "password")),
              button("Sign in")
            )
        }
      ),
    // The original lookup survives acknowledged commit even if its WebSocket
    // completion command is lost. Recovery performs fresh bound authorization;
    // checking an uncommitted attempt fences it and requires a fresh sign-in.
    page.ceremony.toSeq.map(id =>
      form(
        method := "post",
        action := "/reference-submit-unavailable",
        ActionForms.onSubmit[Future, Page, Any, UUID](recoverAction, onRejected),
        hidden("ceremony", id),
        button("Recover this attempt")
      )
    ),
    if (page.recoveryPending) p("Keep this attempt until its outcome is known.") else beginForm
  )
  private def document(page: Page): Document.Node[Binding] = Html(
    head(title("Spoonbill browser authentication reference")),
    body(
      h1(if (page.protectedPage) "Protected page" else "Browser authentication reference"),
      p(page.message),
      page.account match {
        case None => signIn(page)
        case Some(name) =>
          div(
            p(s"Authenticated account: $name"),
            p(s"This view's last confirmed increment: ${page.counter}"),
            if (page.mutationUnresolved) p("This view has an unresolved protected mutation.")
            else
              form(
                ActionForms.onSubmit[Future, Page, Any, Unit, P](
                  incrementAction,
                  backend.authority,
                  onRejected
                ),
                button("Protected increment")
              ),
            a(href := "/protected", "Open protected page"),
            span(" · "),
            a(href := "/sign-out", "Sign out")
          )
      },
      p(a(href := "/", "Public page"))
    )
  )

  val config: SpoonbillServiceConfig[Future, Page, Any] = SpoonbillServiceConfig(
    stateLoader = StateLoader[Future, Page] { (_, request) =>
      if (request.pq.asPath == Root / "protected") {
        backend.protectedPage(request).map {
          case Right(principal) => Page(protectedPage = true, account = Some(backend.displayName(principal)))
          case Left(_)          => throw new SessionAccessDenied
        }
      } else backend.initialPage(request)
    },
    stateStorage = backend.storage,
    document = document,
    router = Router[Future, Page](
      fromState = { case page => if (page.protectedPage) Root / "protected" else Root },
      toState = {
        case Root               => page => Future.successful(page.copy(protectedPage = false))
        case Root / "protected" => page => Future.successful(page.copy(protectedPage = true))
      }
    ),
    sessionAccessControl = Some(backend.accessControl),
    authenticationCompletion = Some(backend.completionConfig)
  )

  def close(): Future[Unit] = backend.close()
}
