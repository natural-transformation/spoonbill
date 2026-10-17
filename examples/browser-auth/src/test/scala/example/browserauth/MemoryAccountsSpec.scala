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
  private def cookie(accounts: MemoryAccounts[Future], receipt: CommittedAuthentication): Future[String] =
    cookie(accounts, receipt.completionId)
  private def cookie(accounts: MemoryAccounts[Future], completionId: UUID): Future[String] = {
    val service = spoonbillService(new Application[Future](accounts).config)
    Stream(Bytes.wrap(completionId.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
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

  it should "replace the exact session and consume encrypted recovery results once under the activated successor" in fixture {
    (accounts, anonymous, login) =>
      val provider    = accounts.browser.persistence
      val support     = provider.asInstanceOf[BrowserReplacementSupport[Future, Principal]]
      val oldToken    = await(cookie(accounts, completed(await(accounts.signIn(login, "alice", "password")))))
      val oldIdentity = accepted(await(provider.capture("b" * 43, Some(oldToken), activate = true)))
      val oldView = accepted(
        await(
          provider.claim(oldIdentity, anonymous.qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)
        )
      )
      val wrong = accepted(await(support.beginReplacement(oldView, SecurityChange.RegenerateRecovery.purpose)))
      await(accounts.replace(wrong, MemoryReplacementTestAccess.invocation(oldView), "incorrect")) shouldBe a[
        SessionReplacement.Rejected
      ]
      await(provider.authorize(oldView)).isRight shouldBe true
      val ceremony   = accepted(await(support.beginReplacement(oldView, SecurityChange.RegenerateRecovery.purpose)))
      val invocation = MemoryReplacementTestAccess.invocation(oldView)
      val handoff = await(accounts.replace(ceremony, invocation, "password")) match {
        case SessionReplacement.Completed(value) => value
        case other                               => fail(s"Replacement did not complete: $other")
      }
      await(provider.authorize(oldView)).isLeft shouldBe true
      await(accounts.increment(oldView)).isLeft shouldBe true
      await(accounts.consumeRecovery(oldView, MemoryReplacementTestAccess.invocation(oldView))).isLeft shouldBe true
      await(accounts.replace(ceremony, invocation, "password")) should not be a[SessionReplacement.Completed]
      val newToken = await(cookie(accounts, handoff.completionId))
      await(provider.capture("b" * 43, Some(newToken), activate = false)).isLeft shouldBe true
      val active = accepted(await(provider.capture("b" * 43, Some(newToken), activate = true)))
      active.principal.map(_.value.name) shouldBe Some("alice")
      active.principal.get.securityVersion shouldBe oldIdentity.principal.get.securityVersion + 1
      val successor = accepted(
        await(provider.claim(active, anonymous.qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
      )
      await(accounts.consumeRecovery(successor, invocation)).isLeft shouldBe true
      val consumption = MemoryReplacementTestAccess.invocation(successor)
      val consuming   = accounts.consumeRecovery(successor, consumption)
      val record      = accepted(await(consuming)).get
      record.completionId should not be handoff.completionId
      record.toString shouldBe "ConsumedRecoveryResult(<redacted>)"
      record.claim(MemoryReplacementTestAccess.invocation(successor), java.time.Instant.now()) shouldBe None
      record.claim(consumption, java.time.Instant.now()).nonEmpty shouldBe true
      record.claim(consumption, java.time.Instant.now()) shouldBe None
      accepted(await(consuming)).get.claim(consumption, java.time.Instant.now()) shouldBe None
      classOf[RecoveryResults.Consumed].getDeclaredFields
        .map(_.getType)
        .toVector should not contain classOf[RecoveryResults.Record]
      accepted(
        await(accounts.consumeRecovery(successor, MemoryReplacementTestAccess.invocation(successor)))
      ) shouldBe None
      accepted(await(accounts.increment(successor))) shouldBe 1
      await(provider.capture("b" * 43, Some(oldToken), activate = true)).isLeft shouldBe true
  }

  it should "apply enrollment, factor replacement and disabling through the same atomic replacement protocol" in {
    Vector(SecurityChange.EnableFactor, SecurityChange.ReplaceFactor, SecurityChange.DisableFactor).foreach {
      operation =>
        fixture { (accounts, anonymous, login) =>
          val provider = accounts.browser.persistence
          val support  = provider.asInstanceOf[BrowserReplacementSupport[Future, Principal]]
          val username = if (operation == SecurityChange.EnableFactor) "alice" else "bob"
          val signedIn = await(accounts.signIn(login, username, "password")) match {
            case _: SignIn.Challenge =>
              val challenge = accepted(await(provider.resume(anonymous, login.id, "sign-in")))
              completed(await(accounts.factor(challenge, "123456")))
            case value => completed(value)
          }
          val oldToken    = await(cookie(accounts, signedIn))
          val oldIdentity = accepted(await(provider.capture("b" * 43, Some(oldToken), activate = true)))
          val predecessor = accepted(
            await(
              provider.claim(oldIdentity, anonymous.qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)
            )
          )
          val ceremony = accepted(await(support.beginReplacement(predecessor, operation.purpose)))
          val handoff =
            await(accounts.replace(ceremony, MemoryReplacementTestAccess.invocation(predecessor), "password")) match {
              case SessionReplacement.Completed(value) => value
              case other                               => fail(s"$operation did not complete: $other")
            }
          val token  = await(cookie(accounts, handoff.completionId))
          val active = accepted(await(provider.capture("b" * 43, Some(token), activate = true)))
          val successor = accepted(
            await(provider.claim(active, anonymous.qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
          )
          accepted(
            await(accounts.consumeRecovery(successor, MemoryReplacementTestAccess.invocation(successor)))
          ).nonEmpty shouldBe (operation != SecurityChange.DisableFactor)
          accepted(await(provider.logout("b" * 43, Some(token))))
          val freshIdentity = accepted(await(provider.capture("b" * 43, None, activate = false)))
          val freshView = accepted(
            await(
              provider.claim(
                freshIdentity,
                anonymous.qsid,
                ConnectionId.fromUuid(UUID.randomUUID()),
                existingOnly = true
              )
            )
          )
          val freshLogin = accepted(await(provider.begin(freshView, "sign-in")))
          val result     = await(accounts.signIn(freshLogin, username, "password"))
          if (operation == SecurityChange.DisableFactor) result shouldBe a[SignIn.Completed]
          else {
            result shouldBe a[SignIn.Challenge]
            val challenged = accepted(await(provider.resume(freshView, freshLogin.id, "sign-in")))
            val factor     = if (operation == SecurityChange.EnableFactor) "123456" else "654321"
            await(accounts.factor(challenged, factor)) shouldBe a[SignIn.Completed]
          }
        }
    }
  }
}
