package spoonbill.zio.http

import ZioHttpKeepAliveFixture.{ec, effect, runtime, AppTask}
import avocet.Document
import avocet.dsl.*
import avocet.dsl.html.*
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import spoonbill.{ActionForms, Context, Router, SensitiveRegion}
import spoonbill.action.*
import spoonbill.auth.*
import spoonbill.security.transaction.{OperationError, OperationProtocolException}
import spoonbill.sensitive.{Audience, DisclosureOutcome, Purpose, RegionId, SensitiveAccess, SensitivePayload}
import spoonbill.server.{HttpResponse, SessionAccessDenied, SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization.*
import spoonbill.web.PathAndQuery.{/, Root}
import zio.ZIO
import zio.http.Server

/**
 * Synthetic browser-authentication page served by ZIO HTTP for
 * `scripts/test-zio-http-browser-auth.sh`. One in-memory account (alice /
 * password); no database, external account or real credential.
 */
object ZioHttpBrowserAuthFixture {
  final case class Principal(subject: UUID, name: String)
  final case class Page(
    account: Option[String] = None,
    ceremony: Option[CeremonyId] = None,
    dashboard: Boolean = false,
    rejected: Boolean = false,
    disclosures: Int = 0,
    message: String = ""
  )
  private final case class Host(
    sessions: Map[UUID, Instant] = Map.empty,
    consumed: Set[UUID] = Set.empty,
    replacements: Int = 0
  )

  private type Binding = Context.Binding[AppTask, Page, Any]
  private val alice   = Principal(UUID.fromString("00000000-0000-4000-8000-00000000a11c"), "alice")
  private val region  = RegionId.parse("recovery").toOption.get
  private val purpose = Purpose.parse("recovery-codes").toOption.get
  private val codes   = SensitivePayload.textList(Vector("synthetic-code-1", "synthetic-code-2")).toOption.get

  private def name(value: String)  = ActionName.parse(value).toOption.get
  private def field(value: String) = FieldName.parse(value).toOption.get

  final class Application(origin: String) {
    private val provider = new InMemoryBrowserPersistence[AppTask, Host, Principal](
      Host(),
      new InMemoryBrowserPersistence.Host[Host, Principal] {
        def resolve(state: Host, sessionId: UUID, now: Instant): Option[HostPrincipal[Principal]] =
          state.sessions
            .get(sessionId)
            .filter(now.isBefore)
            .map(HostPrincipal(alice, alice.subject.toString, 1L, 1L, _))
      },
      () => Instant.now(),
      () => UUID.randomUUID(),
      scope = BrowserProviderScope("zio-http-fixture", "session")
    )
    private val sensitive = new SensitiveAccess[AppTask, Page] {
      def authorize(requested: Purpose, page: Page): AppTask[Audience] =
        if (requested == purpose && page.account.contains(alice.name)) ZIO.succeed(Audience.fromUuid(alice.subject))
        else ZIO.fail(new SessionAccessDenied)
    }
    val browser = new BrowserAuthentication[AppTask, Page, Principal](
      provider,
      BrowserPresentation(
        () => Page(),
        (page, principal) => !page.dashboard || principal.nonEmpty,
        (page, principal, continuation) => {
          val base      = if (page.account != principal.map(_.name)) Page(dashboard = page.dashboard) else page
          val connected = base.copy(account = principal.map(_.name))
          if (principal.nonEmpty) connected.copy(ceremony = None)
          else continuation.fold(connected)(value => connected.copy(ceremony = Some(value.id)))
        }
      ),
      BrowserConfiguration(provider.scope, Set(origin), secureCookies = false),
      sensitiveAccess = view => Option.when(view.principal.nonEmpty)(sensitive)
    )

    private val actions                                     = new Actions[AppTask, Page, Principal]
    private val onRejected: ActionRejection => Page => Page = _ => _.copy(message = "Rejected.")
    private val ceremonyInput = InputSchema
      .text(field("ceremony"), 36)
      .validate(ValidationCode.parse("invalid-identifier").toOption.get)(value =>
        scala.util.Try(UUID.fromString(value)).isSuccess
      )
      .map(value => CeremonyId(UUID.fromString(value)))

    private def signIn(ceremony: BrowserCeremony, username: String, password: String): AppTask[UiOutcome[Page]] =
      if (username != alice.name || password != "password")
        ZIO.succeed(UiOutcome.update(_.copy(rejected = true, message = "Rejected.")))
      else
        BrowserCredentials
          .generate[AppTask]
          .flatMap { credential =>
            provider.prepare(ceremony) { tx =>
              if (tx.hostState.consumed.contains(ceremony.id.value))
                throw new OperationProtocolException(OperationError.HostDenied)
              val sessionId = UUID.randomUUID()
              val expires   = tx.now.plusSeconds(600)
              tx.modify(state =>
                state.copy(
                  sessions = state.sessions.updated(sessionId, expires),
                  consumed = state.consumed + ceremony.id.value
                )
              )
              new HostSessionPrepared(sessionId, UUID.randomUUID(), expires, credential)
            }
          }
          .map {
            case Right(receipt) => browser.complete(receipt)
            case Left(_)        => UiOutcome.update(_.copy(message = "Sign-in was not accepted."))
          }

    private val begin = actions.public(name("fixture.begin"), InputSchema.empty, PublicPolicy.allow[AppTask, Unit]) {
      (_, context) =>
        browser.begin(context).map {
          case Right(ceremony) =>
            UiOutcome.update[Page](_.copy(ceremony = Some(ceremony.id), message = "Sign-in started."))
          case Left(_) => UiOutcome.update[Page](_.copy(message = "Sign-in could not be started."))
        }
    }
    private val passwordInput =
      ceremonyInput.zip(InputSchema.text(field("username"), 64)).zip(InputSchema.secret(field("password"), 256))
    private val password = actions.public(
      name("fixture.password"),
      passwordInput,
      PublicPolicy.allow[AppTask, ((CeremonyId, String), Secret)]
    ) { case (((id, username), secret), context) =>
      browser.resume(context, id).flatMap {
        case Right(ceremony) => secret.withValue(value => signIn(ceremony, username, value))
        case Left(_)         => ZIO.succeed(UiOutcome.update[Page](_.copy(message = "Sign-in expired.")))
      }
    }
    private val disclose = actions.authenticated(
      name("fixture.disclose"),
      InputSchema.empty,
      ActionPolicy[AppTask, Unit, Principal]((_, _) => ZIO.succeed(AccessDecision.Allowed))
    ) { (_, context) =>
      ZIO.succeed(context.presentSensitive[Page](region, purpose, codes, 1.minute) {
        case DisclosureOutcome.BrowserProcessed =>
          StateUpdate(page => page.copy(disclosures = page.disclosures + 1, message = "Recovery codes disclosed."))
        case other => StateUpdate(_.copy(message = s"Disclosure outcome: $other"))
      })
    }

    private def replacementAction(interruptObserver: Boolean, rollbackObserver: Boolean = false) =
      actions.authenticated(
        name(
          if (rollbackObserver) "fixture.replace-rolled-back"
          else if (interruptObserver) "fixture.replace-interrupted"
          else "fixture.replace"
        ),
        InputSchema.secret(field("replacement-password"), 256),
        ActionPolicy[AppTask, Secret, Principal]((_, _) => ZIO.succeed(AccessDecision.Allowed))
      ) { (secret, context) =>
        // Capture the predecessor before checking this synthetic password proof.
        // This fixture tests the atomic boundary, not a production verifier.
        browser.beginReplacement(context, "fixture-session-replacement").flatMap {
          case Left(_) => ZIO.succeed(UiOutcome.update[Page](_.copy(message = "Replacement could not begin.")))
          case Right(ceremony) =>
            secret.withValue { value =>
              if (value != "password") ZIO.succeed(UiOutcome.update[Page](_.copy(message = "Replacement rejected.")))
              else
                for {
                  challenged <- provider.bindReplacementChallenge(
                                  ceremony,
                                  BrowserChallenge(alice.subject.toString, 1L, 1L, ceremony.id.value.toString)
                                )
                  invocation <- browser.replacementInvocation(context)
                  credential <- BrowserCredentials.generate[AppTask]
                  outcome <- (challenged, invocation) match {
                               case (Right(proof), Right(owner)) =>
                                 provider
                                   .prepareReplacement(proof, owner) { tx =>
                                     val predecessor = owner.authority.sessionId
                                       .getOrElse(throw new OperationProtocolException(OperationError.HostDenied))
                                     if (
                                       tx.hostState.consumed.contains(proof.id.value) ||
                                       !tx.hostState.sessions.get(predecessor).exists(tx.now.isBefore)
                                     )
                                       throw new OperationProtocolException(OperationError.HostDenied)
                                     val successor = UUID.randomUUID()
                                     val expires   = tx.now.plusSeconds(600)
                                     tx.modify(state =>
                                       state.copy(
                                         sessions = state.sessions.removed(predecessor).updated(successor, expires),
                                         consumed = state.consumed + proof.id.value,
                                         replacements = state.replacements + 1
                                       )
                                     )
                                     if (rollbackObserver)
                                       throw new OperationProtocolException(OperationError.HostDenied)
                                     new HostSessionPrepared(successor, UUID.randomUUID(), expires, credential)
                                   }
                                   .flatMap {
                                     case Right(_) if interruptObserver =>
                                       ZIO.fail(new IllegalStateException("Synthetic replacement observer lost"))
                                     case Right(handoff) => ZIO.succeed(browser.completeReplacement(handoff))
                                     // Model an observer that cannot establish whether its rolled-back
                                     // transaction committed. The transport must reconcile explicitly.
                                     case Left(_) if rollbackObserver =>
                                       ZIO.succeed(browser.recoverReplacementOutcome(proof.id))
                                     case Left(_) =>
                                       ZIO.succeed(
                                         UiOutcome.update[Page](_.copy(message = "Replacement was not accepted."))
                                       )
                                   }
                               case _ => ZIO.succeed(UiOutcome.update[Page](_.copy(message = "Replacement expired.")))
                             }
                } yield outcome
            }
        }
      }
    private val replace            = replacementAction(interruptObserver = false)
    private val interruptedReplace = replacementAction(interruptObserver = true)
    private val rolledBackReplace  = replacementAction(interruptObserver = false, rollbackObserver = true)

    private def document(page: Page): Document.Node[Binding] = Html(
      head(title("ZIO HTTP browser authentication")),
      body(
        h1(if (page.dashboard) "Dashboard" else "ZIO HTTP browser authentication"),
        p(id := "message", page.message),
        page.account match {
          case None =>
            div(
              id := "anonymous-controls",
              // Insert a sibling before setup, so the retained browser tree no
              // longer matches BrowserPresentation.fresh() on authentication.
              Option.when(page.rejected)(div(id := "anonymous-error", "Invalid password. Try again.")).toSeq,
              div(
                id := "anonymous-setup",
                page.ceremony.toSeq.map { ceremony =>
                  form(
                    id     := "anonymous-form",
                    method := "post",
                    action := "/no-http-proof-submission",
                    ActionForms.onSubmit[AppTask, Page, Any, ((CeremonyId, String), Secret)](password, onRejected),
                    input(`type` := "hidden", avocet.dsl.html.name := "ceremony", value := ceremony.value.toString),
                    label("Account", input(avocet.dsl.html.name := "username", `type` := "text")),
                    label("Password", input(avocet.dsl.html.name := "password", `type` := "password")),
                    button("Sign in")
                  )
                },
                form(ActionForms.onSubmit[AppTask, Page, Any, Unit](begin, onRejected), button("Begin sign-in"))
              )
            )
          case Some(account) =>
            div(
              p(s"Authenticated account: $account"),
              p(id := "disclosure-count", s"Successful disclosures: ${page.disclosures}"),
              form(
                ActionForms.onSubmit[AppTask, Page, Any, Unit, Principal](disclose, browser.authority, onRejected),
                button("Show recovery codes")
              ),
              SensitiveRegion[Binding](region),
              form(
                id := "replacement-form",
                ActionForms.onSubmit[AppTask, Page, Any, Secret, Principal](replace, browser.authority, onRejected),
                label("Confirm password", input(avocet.dsl.html.name := "replacement-password", `type` := "password")),
                button("Replace session")
              ),
              form(
                id := "interrupted-replacement-form",
                ActionForms.onSubmit[AppTask, Page, Any, Secret, Principal](
                  interruptedReplace,
                  browser.authority,
                  onRejected
                ),
                label(
                  "Confirm password for synthetic interrupted observer",
                  input(avocet.dsl.html.name := "replacement-password", `type` := "password")
                ),
                button("Replace with lost observer")
              ),
              form(
                id := "rolled-back-replacement-form",
                ActionForms.onSubmit[AppTask, Page, Any, Secret, Principal](
                  rolledBackReplace,
                  browser.authority,
                  onRejected
                ),
                label(
                  "Confirm password for synthetic rollback",
                  input(avocet.dsl.html.name := "replacement-password", `type` := "password")
                ),
                button("Recover rolled-back replacement")
              ),
              // A native GET submission is a real HTTP navigation, never client-side routing.
              form(
                ActionForms.nativeSubmit[AppTask, Page, Any],
                method := "get",
                action := "/dashboard",
                button("Open dashboard")
              )
            )
        }
      )
    )

    val config: SpoonbillServiceConfig[AppTask, Page, Any] = browser.configure(
      SpoonbillServiceConfig[AppTask, Page, Any](
        stateLoader = StateLoader[AppTask, Page] { (_, request) =>
          browser.currentPrincipal(request).flatMap {
            case Right(current) =>
              ZIO.succeed(Page(account = current.map(_.name), dashboard = request.pq.asPath == Root / "dashboard"))
            case Left(_) => ZIO.fail(new SessionAccessDenied)
          }
        },
        document = document,
        router = Router[AppTask, Page](
          fromState = { case page => if (page.dashboard) Root / "dashboard" else Root },
          toState = {
            case Root               => page => ZIO.succeed(page.copy(dashboard = false))
            case Root / "dashboard" => page => ZIO.succeed(page.copy(dashboard = true))
          }
        ),
        http = {
          case request if request.pq.asPath == Root / "health" => HttpResponse.ok[AppTask]("ready")
          case request if request.pq.asPath == Root / "fixture" / "replacements" =>
            provider.inspectHost.flatMap {
              case Right(state) => HttpResponse.ok[AppTask](state.replacements.toString)
              case Left(_)      => ZIO.fail(new SessionAccessDenied)
            }
        },
        reporter = ZioHttpKeepAliveFixture.silentReporter
      )
    )
  }

  /**
   * Usage: ZioHttpBrowserAuthFixture PORT. The origin defaults to
   * http://localhost:PORT.
   */
  def main(args: Array[String]): Unit = {
    val port   = args.headOption.map(_.toInt).getOrElse(throw new IllegalArgumentException("Pass a loopback port"))
    val origin = sys.env.getOrElse("SPOONBILL_AUTH_ORIGIN", s"http://localhost:$port")
    val app    = new Application(origin)
    val routes = new ZioHttpSpoonbill[Any].service(app.config)
    zio.Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          ZIO.succeed(println(s"Synthetic ZIO HTTP browser-auth fixture starting at $origin")) *>
            Server.serve(routes).provide(Server.defaultWith(_.binding("127.0.0.1", port)))
        )
        .getOrThrowFiberFailure()
    }
  }
}
