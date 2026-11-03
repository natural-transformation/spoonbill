package spoonbill.auth

import _root_.cats.effect.{Deferred, IO, Ref}
import _root_.cats.effect.unsafe.implicits.global
import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.cats.IO3Effect
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Cancellation at the coordinator's integration boundary with a cancelable
 * effect. The gate's own cancellation tests cover one turn; these cover a
 * check in a read turn whose settlement is a later write turn.
 */
final class BrowserAuthenticationIOSpec extends AnyFlatSpec with Matchers {
  "A canceled concurrent check" should "settle its denied view before the cancellation completes" in {
    given Effect[IO]       = new IO3Effect(global)
    given ExecutionContext = global.compute
    val test = for {
      entered      <- Deferred[IO, Unit]
      releaseCheck <- Deferred[IO, Unit]
      failCheck    <- Ref.of[IO, Boolean](false)
      _ <- IO.defer {
        val ids      = new AtomicLong()
        val now      = Instant.parse("2026-10-11T00:00:00Z")
        val settings = BrowserConfiguration(BrowserProviderScope("io", "cancellation"), Set("https://example.test"))
        val underlying = new InMemoryBrowserPersistence[IO, Unit, String](
          (),
          new InMemoryBrowserPersistence.Host[Unit, String] {
            def resolve(state: Unit, id: UUID, time: Instant): Option[HostPrincipal[String]] = None
          },
          () => now,
          () => new UUID(0L, ids.incrementAndGet()),
          scope = settings.scope
        )
        // Opts in to concurrent checks and, on demand, requests cancellation
        // inside the masked provider call before denying. That leaves the same
        // pending cancellation as an external cancel, without a scheduling race.
        val persistence = new BrowserPersistence[IO, String] {
          def scope                                                  = underlying.scope
          def capabilities                                           = underlying.capabilities
          override private[spoonbill] def checkConcurrency           = Some(BrowserCheckConcurrency(2))
          def accepts(receipt: CommittedAuthentication)              = underlying.accepts(receipt)
          def capture(binding: String, token: Option[String], activate: Boolean) =
            underlying.capture(binding, token, activate)
          def claim(identity: BrowserIdentity[String], qsid: Qsid, owner: ConnectionId, existingOnly: Boolean) =
            underlying.claim(identity, qsid, owner, existingOnly)
          def authorize(view: BrowserView[String]): IO[Either[BrowserFailure, BrowserIdentity[String]]] =
            failCheck.get.flatMap {
              case false => underlying.authorize(view)
              case true =>
                entered.complete(()).flatMap(_ => releaseCheck.get).flatMap(_ => IO.canceled)
                  .flatMap(_ => IO.pure(Left(BrowserFailure.Denied)))
            }
          def release(view: BrowserView[String])                          = underlying.release(view)
          def begin(view: BrowserView[String], purpose: String)           = underlying.begin(view, purpose)
          def bindChallenge(ceremony: BrowserCeremony, challenge: BrowserChallenge) =
            underlying.bindChallenge(ceremony, challenge)
          def resume(view: BrowserView[String], id: CeremonyId, purpose: String) = underlying.resume(view, id, purpose)
          def pending(view: BrowserView[String])                                 = underlying.pending(view)
          def recover(view: BrowserView[String], id: CeremonyId)                 = underlying.recover(view, id)
          def deliver(id: UUID, binding: String)                                 = underlying.deliver(id, binding)
          def logout(binding: String, token: Option[String])                     = underlying.logout(binding, token)
          def maintenance()                                                      = underlying.maintenance()
          def close()                                                            = underlying.close()
        }
        val browser = new BrowserAuthentication[IO, String, String](
          persistence,
          BrowserPresentation(() => "fresh", (_, _) => true, (state, _, _) => state),
          settings,
          clock = () => now
        )
        val config = browser.configure(
          SpoonbillServiceConfig[IO, String, Any](
            stateLoader = StateLoader.default("fresh"),
            document = state => Html(body(div(state)))
          )
        )
        val qsid = Qsid("device", "view")
        val request: HttpRequest[IO] = Request(
          Request.Method.Get,
          PathAndQuery.Root,
          Seq("Origin" -> "https://example.test"),
          None,
          Stream.empty[IO, Bytes],
          "spoonbill_binding=" + ("b" * 43)
        )
        val owner = ConnectionId.fromUuid(new UUID(1L, 1L))
        val exercise = for {
          guard           <- config.sessionAccessControl.get.open(qsid, request, owner)
          manager         <- config.stateStorage.get(qsid.deviceId, qsid.sessionId)
          _               <- failCheck.set(true)
          worker          <- guard.authorize("fresh").start
          _               <- entered.get
          _               <- releaseCheck.complete(())
          outcome         <- worker.join
          resources       <- underlying.resources
          oldPresentation <- manager.read[String](Id.TopLevel).attempt
          _ <- IO {
            // The settlement is masked to its end, so the fiber reports the denial
            // or the cancellation, never a success, and before any close or
            // maintenance the denied view is released and its presentation
            // discarded: the cancellation could not skip them.
            withClue(s"outcome=$outcome: ")(outcome.isSuccess shouldBe false)
            (resources.ownedViews, oldPresentation.isLeft) shouldBe ((0, true))
          }
        } yield ()
        exercise.guarantee(browser.close())
      }
    } yield ()
    test.timeout(10.seconds).unsafeRunSync()
  }
}
