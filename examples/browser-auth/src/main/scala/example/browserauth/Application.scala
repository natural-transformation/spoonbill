package example.browserauth

import avocet.Document
import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import scala.concurrent.ExecutionContext
import spoonbill.{ActionForms, Context, Router}
import spoonbill.action.*
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.security.transaction.TransactionFailure
import spoonbill.server.{SessionAccessDenied, SpoonbillServiceConfig, StateLoader}
import spoonbill.web.PathAndQuery.{/, Root}

/**
 * Complete shared Scala UI; there is no application authentication JavaScript.
 */
final class Application[F[_]](val accounts: Accounts[F])(using F: Effect[F], ec: ExecutionContext) {
  private type Binding = Context.Binding[F, Page, Any]
  private val actions              = new Actions[F, Page, Principal]
  private val browser              = accounts.browser
  private def name(value: String)  = ActionName.parse(value).toOption.get
  private def field(value: String) = FieldName.parse(value).toOption.get
  private val invalidId            = ValidationCode.parse("invalid-identifier").toOption.get
  private val ceremonyInput = InputSchema
    .text(field("ceremony"), 36)
    .validate(invalidId)(value => scala.util.Try(UUID.fromString(value)).isSuccess)
    .map(value => CeremonyId(UUID.fromString(value)))
  private val passwordInput = ceremonyInput
    .zip(InputSchema.text(field("username"), 64))
    .zip(InputSchema.secret(field("password"), 256))
  private val factorInput = ceremonyInput.zip(InputSchema.secret(field("factor"), 6))
  private val onRejected: ActionRejection => Page => Page = _ =>
    _.copy(message = "This request was rejected. Recover this attempt or begin a new sign-in.")
  private def result(value: SignIn): UiOutcome[Page] = value match {
    case SignIn.Completed(receipt) => browser.complete(receipt)
    case SignIn.Challenge(id, reference) =>
      UiOutcome.update(
        _.copy(
          ceremony = Some(id),
          challenge = Some(reference),
          recoveryPending = false,
          message = "Enter the factor for this original sign-in."
        )
      )
    case SignIn.Unresolved(id) =>
      UiOutcome.update(
        _.copy(
          ceremony = Some(id),
          recoveryPending = true,
          message = "Recover this attempt without resubmitting credentials."
        )
      )
    case SignIn.Rejected(message) => UiOutcome.update(_.copy(message = message))
  }
  private val begin = actions.public(name("example.begin"), InputSchema.empty, PublicPolicy.allow[F, Unit]) {
    (_, context) =>
      browser.begin(context).map {
        case Right(ceremony) =>
          UiOutcome.update[Page](
            _.copy(
              ceremony = Some(ceremony.id),
              challenge = None,
              recoveryPending = false,
              message = "Sign-in started."
            )
          )
        case Left(_) => result(SignIn.Rejected("Sign-in could not be started."))
      }
  }
  private val password =
    actions.public(name("example.password"), passwordInput, PublicPolicy.allow[F, ((CeremonyId, String), Secret)]) {
      (input, context) =>
        val ((id, username), secret) = input
        browser.resume(context, id).flatMap {
          case Right(ceremony)           => secret.withValue(value => accounts.signIn(ceremony, username, value)).map(result)
          case Left(BrowserFailure.Used) => F.pure(result(SignIn.Unresolved(id)))
          case Left(_)                   => F.pure(result(SignIn.Rejected("Sign-in expired or was revoked.")))
        }
    }
  private val factor =
    actions.public(name("example.factor"), factorInput, PublicPolicy.allow[F, (CeremonyId, Secret)]) {
      (input, context) =>
        val (id, secret) = input
        browser.resume(context, id).flatMap {
          case Right(ceremony)           => secret.withValue(value => accounts.factor(ceremony, value)).map(result)
          case Left(BrowserFailure.Used) => F.pure(result(SignIn.Unresolved(id)))
          case Left(_)                   => F.pure(result(SignIn.Rejected("This challenge expired or was revoked.")))
        }
    }
  private val recover = actions.public(name("example.recover"), ceremonyInput, PublicPolicy.allow[F, CeremonyId]) {
    (id, context) =>
      browser.recover(context, id).map {
        case Right(BrowserRecovery.Committed(receipt)) => browser.complete(receipt)
        case Right(BrowserRecovery.NotCommitted) =>
          UiOutcome.update[Page](
            _.copy(
              ceremony = None,
              challenge = None,
              recoveryPending = false,
              message = "This attempt did not commit. Begin a new sign-in."
            )
          )
        case Left(_) => result(SignIn.Rejected("This attempt is unavailable or expired."))
      }
  }
  private val increment = actions.authenticated(
    name("example.increment"),
    InputSchema.empty,
    ActionPolicy[F, Unit, Principal]((_, _) => F.pure(AccessDecision.Allowed))
  ) { (_, context) =>
    browser.currentView(context).flatMap {
      case Left(_) => F.pure(UiOutcome.unchanged[Page])
      case Right(view) =>
        accounts.increment(view).map {
          case Right(count) =>
            UiOutcome.update[Page](_.copy(counter = count, message = "Protected increment committed."))
          case Left(TransactionFailure.CommitUnknown | TransactionFailure.StorageFailure) =>
            UiOutcome.update[Page](
              _.copy(mutationUnresolved = true, message = "The increment's disposition is unknown. Do not repeat it.")
            )
          case Left(_) => UiOutcome.update[Page](_.copy(message = "The protected operation could not be confirmed."))
        }
    }
  }
  private def hidden(id: CeremonyId): Document.Node[Binding] =
    input(`type` := "hidden", avocet.dsl.html.name := "ceremony", value := id.value.toString)
  private def signIn(page: Page): Document.Node[Binding] = div(
    p("Synthetic accounts: alice / password, or bob / password and factor 123456."),
    page.ceremony.toSeq.map { id =>
      div(
        if (page.recoveryPending) p("The original attempt is awaiting recovery.")
        else if (page.challenge.nonEmpty)
          form(
            method := "post",
            action := "/no-http-proof-submission",
            ActionForms.onSubmit[F, Page, Any, (CeremonyId, Secret)](factor, onRejected),
            hidden(id),
            label("Factor", input(avocet.dsl.html.name := "factor", `type` := "password")),
            button("Verify factor")
          )
        else
          form(
            method := "post",
            action := "/no-http-proof-submission",
            ActionForms.onSubmit[F, Page, Any, ((CeremonyId, String), Secret)](password, onRejected),
            hidden(id),
            label("Account", input(avocet.dsl.html.name := "username", `type` := "text")),
            label("Password", input(avocet.dsl.html.name := "password", `type` := "password")),
            button("Sign in")
          ),
        form(
          method := "post",
          action := "/no-http-proof-submission",
          ActionForms.onSubmit[F, Page, Any, CeremonyId](recover, onRejected),
          hidden(id),
          button("Recover this attempt")
        )
      )
    },
    form(ActionForms.onSubmit[F, Page, Any, Unit](begin, onRejected), button("Begin sign-in"))
  )
  private def document(page: Page): Document.Node[Binding] = Html(
    head(title("Spoonbill browser authentication")),
    body(
      h1(if (page.protectedPage) "Protected page" else "Browser authentication"),
      p(page.message),
      page.account match {
        case None => signIn(page)
        case Some(account) =>
          div(
            p(s"Authenticated account: $account"),
            p(s"This view's last confirmed increment: ${page.counter}"),
            if (page.mutationUnresolved) p("This view has an unresolved protected mutation.")
            else
              form(
                ActionForms.onSubmit[F, Page, Any, Unit, Principal](increment, browser.authority, onRejected),
                button("Protected increment")
              ),
            a(href                                              := "/protected", "Open protected page"),
            form(ActionForms.nativeSubmit[F, Page, Any], method := "post", action := "/auth/logout", button("Sign out"))
          )
      },
      a(href := "/", "Public page")
    )
  )
  val config: SpoonbillServiceConfig[F, Page, Any] = browser.configure(
    SpoonbillServiceConfig(
      stateLoader = StateLoader[F, Page] { (_, request) =>
        browser.currentPrincipal(request).map {
          case Right(current) =>
            Page(account = current.map(_.name), protectedPage = request.pq.asPath == Root / "protected")
          case Left(_) => throw new SessionAccessDenied
        }
      },
      document = document,
      router = Router[F, Page](
        fromState = { case page => if (page.protectedPage) Root / "protected" else Root },
        toState = {
          case Root               => page => F.pure(page.copy(protectedPage = false))
          case Root / "protected" => page => F.pure(page.copy(protectedPage = true))
        }
      )
    )
  )
}
