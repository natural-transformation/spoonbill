package example.browserauth

import _root_.cats.effect.{Deferred, IO, Resource}
import _root_.cats.effect.syntax.all.*
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all.*
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.cats.IO3Effect
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.spoonbillService
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Executes the actual generic account implementation and shared Application in
 * native IO. Credential extraction is only through the real HTTP endpoint;
 * application code has no access to BrowserSessionToken's private contents.
 */
class MemoryAccountsIOSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[IO]       = new IO3Effect(global)
  private val binding            = "b" * 43
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected refusal: $error"), identity)
  private def receipt(value: SignIn): CommittedAuthentication = value match {
    case SignIn.Completed(value) => value
    case other                   => fail(s"Sign-in did not complete: $other")
  }
  private def accounts: Resource[IO, MemoryAccounts[IO]] =
    Resource.make(IO.blocking(new MemoryAccounts[IO]()))(_.close())
  private def open(accounts: MemoryAccounts[IO]): IO[(BrowserView[Principal], BrowserCeremony)] = for {
    _        <- accounts.initialize()
    captured <- accounts.browser.persistence.capture(binding, None, activate = false).map(accepted)
    view <-
      accounts.browser.persistence
        .claim(captured, Qsid("io-device", "io-view"), ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false)
        .map(accepted)
    ceremony <- accounts.browser.persistence.begin(view, "sign-in").map(accepted)
  } yield view -> ceremony
  private def cookie(accounts: MemoryAccounts[IO], receipt: CommittedAuthentication): IO[String] = {
    val service = spoonbillService(new Application[IO](accounts).config)
    Stream(Bytes.wrap(receipt.completionId.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      .mat[IO]()
      .flatMap { body =>
        service.http(
          Request(
            Request.Method.Post,
            PathAndQuery.Root / "auth" / "complete",
            Seq("Origin" -> "http://localhost:8080", "Sec-Fetch-Site" -> "same-origin", "Content-Type" -> "text/plain"),
            Some(36L),
            body,
            "spoonbill_binding=" + binding
          )
        )
      }
      .flatMap { response =>
        IO {
          response.status.code shouldBe 204
          response.headers.collectFirst {
            case ("Set-Cookie", value) if value.startsWith("spoonbill_session=") =>
              value.drop("spoonbill_session=".length).takeWhile(_ != ';')
          }.getOrElse(fail("The HTTP endpoint did not issue a session cookie"))
        }.guarantee(response.body.cancel())
      }
  }

  "The native IO application" should "defer proof admission until evaluation and complete password login, cookie delivery, a protected action and logout" in {
    accounts.use { accounts =>
      for {
        context <- open(accounts)
        // Constructing more than the five-attempt rate limit must do no work.
        unexecuted = Vector.fill(8)(accounts.signIn(context._2, "alice", "wrong"))
        completed <- accounts.signIn(context._2, "alice", "password").map(receipt)
        token     <- cookie(accounts, completed)
        captured  <- accounts.browser.persistence.capture(binding, Some(token), activate = true).map(accepted)
        view <- accounts.browser.persistence
                  .claim(captured, context._1.qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)
                  .map(accepted)
        count <- accounts.increment(view).map(accepted)
        _ <- IO {
               unexecuted.size shouldBe 8; captured.principal.map(_.value.name) shouldBe Some("alice"); count shouldBe 1
             }
        _      <- accounts.browser.persistence.logout(binding, Some(token)).map(accepted)
        denied <- accounts.increment(view)
        _      <- IO(denied.isLeft shouldBe true)
      } yield ()
    }.timeout(15.seconds).unsafeRunSync()
  }

  it should "run the original factor challenge and recover its committed delivery without replay" in {
    accounts.use { accounts =>
      for {
        context   <- open(accounts)
        challenge <- accounts.signIn(context._2, "bob", "password")
        _         <- IO(challenge shouldBe a[SignIn.Challenge])
        resumed   <- accounts.browser.persistence.resume(context._1, context._2.id, "sign-in").map(accepted)
        rejected  <- accounts.factor(resumed, "000000")
        _         <- IO(rejected shouldBe a[SignIn.Rejected])
        completed <- accounts.factor(resumed, "123456").map(receipt)
        recovery  <- accounts.browser.persistence.recover(context._1, resumed.id).map(accepted)
        recovered <- IO(recovery match {
                       case BrowserRecovery.Committed(value) => value
                       case _                                => fail("Expected committed recovery")
                     })
        _        <- IO(recovered.completionId shouldBe completed.completionId)
        token    <- cookie(accounts, recovered)
        identity <- accounts.browser.persistence.capture(binding, Some(token), activate = true).map(accepted)
        _        <- IO(identity.principal.map(_.value.name) shouldBe Some("bob"))
      } yield ()
    }.timeout(15.seconds).unsafeRunSync()
  }

  it should "drain admitted native proof work before close despite observer cancellation" in {
    val hashed          = new AtomicInteger()
    val watchingClose   = new AtomicBoolean()
    val closeRegistered = new CountDownLatch(1)
    (for {
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      closed  <- Deferred[IO, Unit]
      effect = new IO3Effect(global) {
                 override def blocking[A](body: => A)(implicit ec: ExecutionContext): IO[A] =
                   entered.complete(()).void *> release.get *> super.blocking { hashed.incrementAndGet(); body }
                 override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
                   super.promise { callback =>
                     register(callback)
                     if (watchingClose.get()) closeRegistered.countDown()
                   }
               }
      _ <- Resource
             .make(IO.blocking(new MemoryAccounts[IO]()(using effect, summon[ExecutionContext])))(accounts =>
               release.complete(()).void *> accounts.close()
             )
             .use { accounts =>
               for {
                 context      <- open(accounts)
                 running      <- accounts.signIn(context._2, "alice", "password").start
                 _            <- entered.get
                 cancellation <- running.cancel.start
                 _            <- IO(watchingClose.set(true))
                 closing      <- accounts.close().guarantee(closed.complete(()).void).start
                 _            <- IO.blocking(closeRegistered.await(5, TimeUnit.SECONDS)).flatMap(ready => IO(ready shouldBe true))
                 notClosed    <- closed.tryGet
                 _            <- IO(notClosed shouldBe None)
                 _            <- release.complete(())
                 _            <- cancellation.joinWithNever
                 _            <- closing.joinWithNever
                 _            <- IO(hashed.get() shouldBe 1)
                 denied       <- accounts.signIn(context._2, "alice", "password")
                 _            <- IO(denied shouldBe a[SignIn.Rejected])
               } yield ()
             }
    } yield ()).timeout(15.seconds).unsafeRunSync()
  }
}
