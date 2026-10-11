package example.browserauth

import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.data.Bytes
import spoonbill.effect.Stream
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.spoonbillService
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Exercises the complete application's host policy through its public API,
 * independently of the browser UI and provider conformance fixtures.
 */
class MemoryAccountsSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext            = ExecutionContext.global
  private def await[A](value: Future[A]): A = Await.result(value, 10.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected refusal: $error"), identity)
  private def fixture(test: (MemoryAccounts[Future], BrowserView[Principal], BrowserCeremony) => Unit): Unit = {
    val accounts = new MemoryAccounts[Future]()
    val provider = accounts.browser.persistence
    try {
      await(accounts.initialize())
      val identity = accepted(await(provider.capture("b" * 43, None, activate = false)))
      val view = accepted(
        await(
          provider.claim(
            identity,
            Qsid("example-device", "example-view"),
            ConnectionId.fromUuid(UUID.randomUUID()),
            existingOnly = false
          )
        )
      )
      test(accounts, view, accepted(await(provider.begin(view, "sign-in"))))
    } finally await(accounts.close())
  }
  private def completed(value: SignIn): CommittedAuthentication = value match {
    case SignIn.Completed(receipt) => receipt
    case other                     => fail(s"Sign-in did not complete: $other")
  }
  private def cookie(accounts: MemoryAccounts[Future], receipt: CommittedAuthentication): Future[String] = {
    val service = spoonbillService(new Application[Future](accounts).config)
    Stream(Bytes.wrap(receipt.completionId.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      .mat[Future]()
      .flatMap { body =>
        service.http(
          Request(
            Request.Method.Post,
            PathAndQuery.Root / "auth" / "complete",
            Seq("Origin" -> "http://localhost:8080", "Sec-Fetch-Site" -> "same-origin", "Content-Type" -> "text/plain"),
            Some(36L),
            body,
            "spoonbill_binding=" + "b" * 43
          )
        )
      }
      .flatMap { response =>
        response.status.code shouldBe 204
        val token = response.headers.collectFirst {
          case ("Set-Cookie", value) if value.startsWith("spoonbill_session=") =>
            value.drop("spoonbill_session=".length).takeWhile(_ != ';')
        }.getOrElse(fail("The completion endpoint did not deliver a session cookie"))
        response.body.cancel().map(_ => token)
      }
  }

  "The memory host example" should "prepare password authentication once and authorize a real protected mutation" in fixture {
    (accounts, oldView, ceremony) =>
      val provider = accounts.browser.persistence
      val receipt  = completed(await(accounts.signIn(ceremony, "alice", "password")))
      val token    = await(cookie(accounts, receipt))
      val identity = accepted(await(provider.capture("b" * 43, Some(token), activate = true)))
      identity.principal.map(_.value.name) shouldBe Some("alice")
      await(provider.authorize(oldView)).isLeft shouldBe true
      val view = accepted(
        await(provider.claim(identity, oldView.qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
      )
      accepted(await(accounts.increment(view))) shouldBe 1
      await(accounts.signIn(ceremony, "alice", "password")) shouldBe a[SignIn.Unresolved]
      accepted(await(provider.logout("b" * 43, Some(token))))
      await(accounts.increment(view)).isLeft shouldBe true
  }

  it should "keep the original ceremony for a factor challenge and reject wrong or repeated proof" in fixture {
    (accounts, view, ceremony) =>
      val provider = accounts.browser.persistence
      await(accounts.signIn(ceremony, "bob", "password")) match {
        case SignIn.Challenge(id, _) => id shouldBe ceremony.id
        case other                   => fail(s"Expected the factor challenge: $other")
      }
      val resumed = accepted(await(provider.resume(view, ceremony.id, "sign-in")))
      resumed.expiresAt shouldBe ceremony.expiresAt
      resumed.challenge.map(_.subject) shouldBe Some(new UUID(0L, 2L).toString)
      await(accounts.factor(resumed, "000000")) shouldBe a[SignIn.Rejected]
      val receipt = completed(await(accounts.factor(resumed, "123456")))
      await(accounts.factor(resumed, "123456")) shouldBe a[SignIn.Unresolved]
      accepted(await(provider.recover(view, ceremony.id))) match {
        case BrowserRecovery.Committed(recovered) => recovered.completionId shouldBe receipt.completionId
        case other                                => fail(s"Expected delivery recovery: $other")
      }
  }

  it should "reject password failure and an original ceremony fenced by logout" in fixture { (accounts, _, ceremony) =>
    await(accounts.signIn(ceremony, "alice", "incorrect")) shouldBe a[SignIn.Rejected]
    accepted(await(accounts.browser.persistence.logout("b" * 43, None)))
    await(accounts.signIn(ceremony, "alice", "password")) shouldBe a[SignIn.Rejected]
  }

  it should "reject challenge subject substitution and an unrelated ceremony purpose" in fixture {
    (accounts, view, ceremony) =>
      val provider = accounts.browser.persistence
      await(accounts.signIn(ceremony, "bob", "password")) shouldBe a[SignIn.Challenge]
      val challenged = accepted(await(provider.resume(view, ceremony.id, "sign-in")))
      await(accounts.signIn(challenged, "alice", "password")) shouldBe a[SignIn.Rejected]
      val unrelated = accepted(await(provider.begin(view, "account-recovery")))
      await(accounts.signIn(unrelated, "alice", "password")) shouldBe a[SignIn.Rejected]
  }
}
