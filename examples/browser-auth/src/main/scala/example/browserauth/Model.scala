package example.browserauth

import java.util.UUID
import spoonbill.action.{AuthenticatedActionContext, UiOutcome}
import spoonbill.auth.*
import spoonbill.security.transaction.TransactionFailure
import spoonbill.snapshot.*
import spoonbill.web.PathAndQuery
import spoonbill.web.PathAndQuery.{/, Root}

final case class Principal(subject: UUID, name: String)

/** Non-secret presentation text, never an authority or redirect destination. */
opaque type PublicContinuation = String
object PublicContinuation {
  def fromText(value: String): PublicContinuation        = value
  extension (value: PublicContinuation) def text: String = value
}

enum PageRoute {
  case Landing, Protected
  case Login(value: PublicContinuation)
  case Information(value: PublicContinuation)

  def continuation: Option[PublicContinuation] = this match {
    case Landing | Protected => None
    case Login(value)        => Some(value)
    case Information(value)  => Some(value)
  }

  /**
   * Total presentation update: routes without a continuation remain unchanged.
   */
  def withContinuation(value: PublicContinuation): PageRoute = this match {
    case Landing | Protected => this
    case Login(_)            => Login(value)
    case Information(_)      => Information(value)
  }

  /**
   * Account changes discard public presentation while preserving protected-page
   * admission.
   */
  def afterAuthorityChange: PageRoute = this match {
    case Protected                           => Protected
    case Landing | Login(_) | Information(_) => Landing
  }

  def toPath: PathAndQuery = {
    def publicPath(path: PathAndQuery, value: PublicContinuation): PathAndQuery =
      if (value.text.isEmpty) path else path.withParam("continue", value.text)
    this match {
      case Landing            => Root
      case Protected          => Root / "protected"
      case Login(value)       => publicPath(Root / "login", value)
      case Information(value) => publicPath(Root / "public", value)
    }
  }
}

object PageRoute {
  val fromPath: PartialFunction[PathAndQuery, PageRoute] = {
    case Root               => PageRoute.Landing
    case Root / "protected" => PageRoute.Protected
    case pq if pq.asPath == Root / "login" =>
      PageRoute.Login(PublicContinuation.fromText(pq.param("continue").getOrElse("")))
    case pq if pq.asPath == Root / "public" =>
      PageRoute.Information(PublicContinuation.fromText(pq.param("continue").getOrElse("")))
  }
}

final case class Page(
  account: Option[String] = None,
  ceremony: Option[CeremonyId] = None,
  challenge: Option[String] = None,
  recoveryPending: Boolean = false,
  route: PageRoute = PageRoute.Landing,
  counter: Int = 0,
  mutationUnresolved: Boolean = false,
  draft: String = "",
  selectedItem: String = "",
  message: String = ""
)

/**
 * Deliberately excludes credentials, principals, ceremonies and live authority.
 */
final case class DraftPresentation(
  draft: String,
  selectedItem: String
) derives StateSchema

object DraftPresentation {
  private def versioned(version: Int) = new SnapshotFormat[DraftPresentation](
    SchemaId.parse("example.workspace").toOption.get,
    SchemaVersion.fromInt(version).toOption.get,
    SnapshotLimits.create(maxUtf8Bytes = 32768, maxStringUtf8Bytes = 16384).toOption.get
  )

  /** Two explicitly compatible writers for the rolling-version fixture. */
  def format(version: Int): SnapshotFormat[DraftPresentation] = {
    require(version >= 1 && version <= 3, "Select synthetic snapshot version 1, 2 or incompatible 3")
    if (version == 3) versioned(version)
    else versioned(version).reading(versioned(if (version == 1) 2 else 1))(Right(_))
  }
  def project(page: Page): DraftPresentation =
    DraftPresentation(page.draft, page.selectedItem)
  def restore(fresh: Page, saved: DraftPresentation): Page =
    fresh.copy(
      draft = saved.draft,
      selectedItem = saved.selectedItem
    )
}

enum SignIn {
  case Completed(receipt: CommittedAuthentication)
  case Challenge(ceremony: CeremonyId, reference: String)
  case Unresolved(ceremony: CeremonyId)
  case Rejected(message: String)
}

enum SecurityChange(val purpose: String) {
  case RegenerateRecovery extends SecurityChange("regenerate-recovery")
  case EnableFactor extends SecurityChange("enable-factor")
  case ReplaceFactor extends SecurityChange("replace-factor")
  case DisableFactor extends SecurityChange("disable-factor")
}

enum SessionReplacement {
  case Completed(handoff: CommittedReplacement)
  case Unresolved(ceremony: CeremonyId)
  case Rejected(message: String)
}

/**
 * Domain integration only. Browser/view/credential-delivery mechanics belong to
 * the library. Both implementations use the exact same Scala UI.
 */
trait Accounts[F[_]] {
  def browser: BrowserAuthentication[F, Page, Principal]
  def signIn(ceremony: BrowserCeremony, username: String, password: String): F[SignIn]
  def factor(ceremony: BrowserCeremony, factor: String): F[SignIn]
  def replace(
    ceremony: BrowserReplacementCeremony,
    invocation: BrowserReplacementInvocation,
    password: String
  ): F[SessionReplacement]
  def consumeRecovery(
    view: BrowserView[Principal],
    invocation: BrowserReplacementInvocation
  ): F[Either[TransactionFailure, Option[RecoveryResults.Consumed]]]

  /**
   * Claim and decrypt a consumed result inside F, then transfer it to the
   * invocation's one-shot disclosure. Constructing a lazy effect must not claim
   * the holder; evaluating it again must not disclose the result again.
   */
  def presentRecovery(
    record: RecoveryResults.Consumed,
    context: AuthenticatedActionContext[Principal]
  ): F[UiOutcome[Page]]
  def increment(view: BrowserView[Principal]): F[Either[TransactionFailure, Int]]
  def currentCount(principal: Principal): F[Int]
  def initialize(): F[Unit]
  def maintenance(): F[Unit] = browser.maintenance()
  def close(): F[Unit]       = browser.close()
}

object Presentation {
  val policy: BrowserPresentation[Page, Principal] = BrowserPresentation(
    () => Page(),
    (page, principal) =>
      page.route match {
        case PageRoute.Protected                                               => principal.nonEmpty
        case PageRoute.Landing | PageRoute.Login(_) | PageRoute.Information(_) => true
      },
    (page, principal, continuation) => {
      val base      = if (page.account != principal.map(_.name)) Page(route = page.route.afterAuthorityChange) else page
      val connected = base.copy(account = principal.map(_.name))
      if (principal.nonEmpty) connected.copy(ceremony = None, challenge = None, recoveryPending = false)
      else
        continuation.fold(connected)(value =>
          connected.copy(
            ceremony = Some(value.id),
            challenge = value.challenge.map(_.reference),
            recoveryPending = value.preparationPending,
            message = if (value.preparationPending) "Recover the interrupted sign-in." else "Continue this sign-in."
          )
        )
    }
  )
}
