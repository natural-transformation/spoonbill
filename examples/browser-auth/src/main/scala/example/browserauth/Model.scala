package example.browserauth

import java.util.UUID
import spoonbill.auth.*
import spoonbill.security.transaction.TransactionFailure

final case class Principal(subject: UUID, name: String)
final case class Page(
  account: Option[String] = None,
  ceremony: Option[CeremonyId] = None,
  challenge: Option[String] = None,
  recoveryPending: Boolean = false,
  protectedPage: Boolean = false,
  counter: Int = 0,
  mutationUnresolved: Boolean = false,
  message: String = ""
)

enum SignIn {
  case Completed(receipt: CommittedAuthentication)
  case Challenge(ceremony: CeremonyId, reference: String)
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
  def increment(view: BrowserView[Principal]): F[Either[TransactionFailure, Int]]
  def initialize(): F[Unit]
  def close(): F[Unit] = browser.close()
}

object Presentation {
  val policy: BrowserPresentation[Page, Principal] = BrowserPresentation(
    () => Page(),
    (page, principal) => !page.protectedPage || principal.nonEmpty,
    (page, principal, continuation) => {
      val base      = if (page.account != principal.map(_.name)) Page(protectedPage = page.protectedPage) else page
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
