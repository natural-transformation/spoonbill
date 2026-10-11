package spoonbill.auth

import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.data.Bytes
import spoonbill.effect.Stream
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.*
import spoonbill.state.BoundedPresentationStore
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

class BrowserAuthenticationFailureSpec extends AnyFlatSpec with Matchers {
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private class Fixture(limits: BoundedPresentationStore.Limits = BoundedPresentationStore.Limits()) {
    private val ids    = new AtomicLong()
    private val time   = Instant.parse("2026-10-11T00:00:00Z")
    val releaseFails   = new AtomicBoolean()
    val authorizeFails = new AtomicBoolean()
    val factoryFails   = new AtomicBoolean()
    val releaseDelay   = new AtomicReference(Option.empty[Promise[Unit]])
    val authorizeDelay = new AtomicReference(Option.empty[Promise[Unit]])
    val releaseError   = new IllegalStateException("Synthetic release failure")
    val authorizeError = new IllegalStateException("Synthetic authority storage failure")
    val config = BrowserConfiguration(
      BrowserProviderScope("test", "failure"),
      Set("https://example.test"),
      maxPendingOperations = 1
    )
    val underlying = new InMemoryBrowserPersistence[Future, Unit, String](
      (),
      new InMemoryBrowserPersistence.Host[Unit, String] {
        def resolve(state: Unit, id: UUID, now: Instant): Option[HostPrincipal[String]] = None
      },
      () => time,
      () => new UUID(0L, ids.incrementAndGet()),
      scope = config.scope
    )
    val persistence = new BrowserPersistence[Future, String] {
      def scope                                     = underlying.scope
      def capabilities                              = underlying.capabilities
      def accepts(receipt: CommittedAuthentication) = underlying.accepts(receipt)
      def capture(binding: String, token: Option[String], activate: Boolean) =
        underlying.capture(binding, token, activate)
      def claim(identity: BrowserIdentity[String], qsid: Qsid, id: ConnectionId, existingOnly: Boolean) =
        underlying.claim(identity, qsid, id, existingOnly)
      def authorize(view: BrowserView[String]) =
        if (authorizeFails.get()) Future.failed(authorizeError)
        else authorizeDelay.get().fold(Future.unit)(_.future).flatMap(_ => underlying.authorize(view))
      def release(view: BrowserView[String]): Future[Unit] =
        if (releaseFails.get()) Future.failed(releaseError)
        else releaseDelay.get().fold(Future.unit)(_.future).flatMap(_ => underlying.release(view))
      def begin(view: BrowserView[String], purpose: String) = underlying.begin(view, purpose)
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
    val browser = new BrowserAuthentication[Future, String, String](
      persistence,
      BrowserPresentation(() => "fresh", (_, _) => true, (state, _, _) => state),
      config,
      storageLimits = limits,
      clock = () => time,
      sensitiveAccess = _ => {
        if (factoryFails.get()) throw new IllegalStateException("Synthetic policy factory failure")
        None
      }
    )
    val configured = browser.configure(
      SpoonbillServiceConfig[Future, String, Any](
        stateLoader = StateLoader.default("fresh"),
        document = state => Html(body(div(state)))
      )
    )
    val viewKey = Qsid("device", "view")
    def request: HttpRequest[Future] = Request(
      Request.Method.Get,
      PathAndQuery.Root,
      Seq("Origin" -> "https://example.test"),
      None,
      Stream.empty[Future, Bytes],
      "spoonbill_binding=" + "b" * 43
    )
    def open(qsid: Qsid = viewKey): SessionGuard[Future, String] = await(
      configured.sessionAccessControl.get
        .open(qsid, request, ConnectionId.fromUuid(new UUID(0L, ids.incrementAndGet())))
    )
    def manager = await(configured.stateStorage.get(viewKey.deviceId, viewKey.sessionId))
    def close(): Unit = {
      releaseFails.set(false); authorizeFails.set(false); factoryFails.set(false)
      releaseDelay.get().foreach(_.trySuccess(())); authorizeDelay.get().foreach(_.trySuccess(()))
      await(browser.close())
    }
  }
  private def withFixture(body: Fixture => Unit): Unit = {
    val fixture = new Fixture
    try body(fixture)
    finally fixture.close()
  }

  "Coordinator cleanup" should "keep a failed release retryable without restoring its discarded presentation" in withFixture {
    f =>
      val guard = f.open()
      val old   = f.manager
      await(old.write(Id("1_2"), "must be discarded"))
      val snapshot = await(old.snapshot)
      f.releaseFails.set(true)
      await(guard.close().failed) shouldBe f.releaseError
      await(f.underlying.resources).ownedViews shouldBe 1
      intercept[BoundedPresentationStore.Rejected](snapshot.apply[String](Id.TopLevel))
      f.releaseFails.set(false)
      await(guard.close())
      await(f.underlying.resources).ownedViews shouldBe 0
      val next = f.open()
      await(f.manager.read[String](Id("1_2"))) shouldBe None
      await(next.close())
  }

  it should "detach snapshots before an asynchronous provider release settles" in withFixture { f =>
    val guard    = f.open()
    val snapshot = await(f.manager.snapshot)
    val release  = Promise[Unit]()
    f.releaseDelay.set(Some(release))
    val closing = guard.close()
    closing.isCompleted shouldBe false
    intercept[BoundedPresentationStore.Rejected](snapshot.apply[String](Id.TopLevel))
    release.success(())
    await(closing)
    await(f.underlying.resources).ownedViews shouldBe 0
  }

  it should "retry ownership cleanup after an unexpected authorization failure" in withFixture { f =>
    val guard = f.open()
    val old   = f.manager
    f.authorizeFails.set(true)
    f.releaseFails.set(true)
    await(guard.authorize("fresh").failed) shouldBe f.authorizeError
    intercept[BoundedPresentationStore.Rejected](await(old.read[String](Id.TopLevel)))
    await(f.underlying.resources).ownedViews shouldBe 1
    f.releaseFails.set(false)
    await(f.browser.maintenance())
    await(f.underlying.resources).ownedViews shouldBe 0
    await(guard.close())
  }

  it should "release a claimed owner when an application policy factory throws" in withFixture { f =>
    f.factoryFails.set(true)
    intercept[IllegalStateException](f.open())
    await(f.underlying.resources).ownedViews shouldBe 0
    f.factoryFails.set(false)
    val guard = f.open()
    await(guard.authorize("fresh"))
    await(guard.close())
  }

  it should "bound failed claims awaiting cleanup before acquiring another provider resource" in {
    val f = new Fixture(BoundedPresentationStore.Limits(active = 1, pending = 1))
    try {
      f.factoryFails.set(true)
      f.releaseFails.set(true)
      intercept[IllegalStateException](f.open(Qsid("device", "first")))
      intercept[IllegalStateException](f.open(Qsid("device", "second")))
      await(f.underlying.resources).ownedViews shouldBe 2
      intercept[SessionAccessDenied](f.open(Qsid("device", "third")))
      await(f.underlying.resources).views shouldBe 2
      f.factoryFails.set(false)
      f.releaseFails.set(false)
      await(f.browser.maintenance())
      await(f.underlying.resources).ownedViews shouldBe 0
      val guard = f.open(Qsid("device", "third"))
      await(guard.close())
    } finally f.close()
  }

  it should "reserve guard release while the ordinary coordinator queue is full" in withFixture { f =>
    val guard = f.open()
    val held  = Promise[Unit]()
    f.authorizeDelay.set(Some(held))
    val running = guard.authorize("fresh")
    val queued  = f.browser.currentPrincipal(f.request)
    val closing = guard.close()
    closing.isCompleted shouldBe false
    held.success(())
    await(running)
    await(queued).isRight shouldBe true
    await(closing)
    await(f.underlying.resources).ownedViews shouldBe 0
  }

  it should "preserve same-identity takeover nodes when disconnected retention is full" in {
    val f = new Fixture(BoundedPresentationStore.Limits(disconnected = 1))
    try {
      val first = f.open()
      await(f.manager.write(Id("1_2"), "retained"))
      val other = f.open(Qsid("device", "other"))
      await(other.close())
      val next = f.open()
      await(f.manager.read[String](Id("1_2"))) shouldBe Some("retained")
      await(first.close())
      await(next.authorize("fresh"))
      await(next.close())
    } finally f.close()
  }
}
