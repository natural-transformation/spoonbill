package spoonbill.auth

import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.action.{AccessDenied, InvocationBinding}
import spoonbill.data.Bytes
import spoonbill.effect.Stream
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.server.*
import spoonbill.state.BoundedPresentationStore
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

class BrowserAuthenticationFailureSpec extends AnyFlatSpec with Matchers {
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private class Fixture(
    limits: BoundedPresentationStore.Limits = BoundedPresentationStore.Limits(),
    ownership: BrowserOwnership = BrowserOwnership.ExclusiveScope,
    allowed: String => Boolean = _ => true,
    concurrency: Option[BrowserCheckConcurrency] = None
  ) {
    private val ids    = new AtomicLong()
    private val time   = Instant.parse("2026-10-11T00:00:00Z")
    val releaseFails   = new AtomicBoolean()
    val releaseCalls   = new AtomicInteger()
    val authorizeFails = new AtomicBoolean()
    val authorizeCalls = new AtomicInteger()
    val authorizeNewGeneration = new AtomicBoolean()
    val factoryFails   = new AtomicBoolean()
    val releaseDelay   = new AtomicReference(Option.empty[Promise[Unit]])
    val authorizeDelay = new AtomicReference(Option.empty[Promise[Unit]])
    // Holds one view's observation open so other coordinator work can be queued behind it.
    val authorizeHold  = new AtomicReference(Option.empty[(ConnectionId, Promise[Unit])])
    val authorizeLog   = new AtomicReference(Vector.empty[ConnectionId])
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
      def capabilities                              = underlying.capabilities.copy(ownership = ownership)
      override private[spoonbill] def checkConcurrency = concurrency
      def accepts(receipt: CommittedAuthentication) = underlying.accepts(receipt)
      def capture(binding: String, token: Option[String], activate: Boolean) =
        underlying.capture(binding, token, activate)
      def claim(identity: BrowserIdentity[String], qsid: Qsid, id: ConnectionId, existingOnly: Boolean) =
        underlying.claim(identity, qsid, id, existingOnly)
      def authorize(view: BrowserView[String]) = {
        authorizeCalls.incrementAndGet()
        authorizeLog.updateAndGet(_ :+ view.connectionId)
        val held = authorizeHold.get().filter(_._1 == view.connectionId).fold(Future.unit)(_._2.future)
        if (authorizeFails.get()) Future.failed(authorizeError)
        else held.flatMap(_ => authorizeDelay.get().fold(Future.unit)(_.future)).flatMap(_ => underlying.authorize(view)).map {
          case Right(identity) if authorizeNewGeneration.get() =>
            // A provider may return a fresh observation of changed authority.
            // Preserve issuer and credentials so the coordinator must compare
            // the captured generation, not only its presentation policy.
            Right(new BrowserIdentity[String](identity.issuer, identity.binding, identity.token,
              identity.generation + 1, identity.host, identity.sessionId))
          case result => result
        }
      }
      def release(view: BrowserView[String]): Future[Unit] =
        if ({ releaseCalls.incrementAndGet(); releaseFails.get() }) Future.failed(releaseError)
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
      BrowserPresentation(() => "fresh", (state, _) => allowed(state), (state, _, _) => state),
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
    def request(binding: String = "b" * 43): HttpRequest[Future] = Request(
      Request.Method.Get,
      PathAndQuery.Root,
      Seq("Origin" -> "https://example.test"),
      None,
      Stream.empty[Future, Bytes],
      "spoonbill_binding=" + binding
    )
    def open(qsid: Qsid = viewKey): SessionGuard[Future, String] = openOwned(qsid)._1
    def openOwned(qsid: Qsid, binding: String = "b" * 43): (SessionGuard[Future, String], ConnectionId) = {
      val owner = ConnectionId.fromUuid(new UUID(0L, ids.incrementAndGet()))
      val guard = await(configured.sessionAccessControl.get.open(qsid, request(binding), owner))
      guard.applicationStateInitialization match {
        case ApplicationStateInitialization.Retained() => ()
        case ApplicationStateInitialization.Reconstructed(acknowledge) => await(acknowledge())
      }
      guard -> owner
    }
    def manager = await(configured.stateStorage.get(viewKey.deviceId, viewKey.sessionId))
    def close(): Unit = {
      releaseFails.set(false); authorizeFails.set(false); factoryFails.set(false)
      releaseDelay.get().foreach(_.trySuccess(())); authorizeDelay.get().foreach(_.trySuccess(()))
      authorizeHold.get().foreach(_._2.trySuccess(()))
      await(browser.close())
    }
  }
  private def withFixture(body: Fixture => Unit): Unit = {
    val fixture = new Fixture
    try body(fixture)
    finally fixture.close()
  }

  "Shared output policy" should "check current and produced presentation in one observation and detach rejected owners" in {
    for ((produced, current, accepted) <- Vector(
      ("fresh", "fresh", true), ("forbidden", "fresh", false), ("fresh", "forbidden", false)
    )) {
      val fixture = new Fixture(ownership = BrowserOwnership.SharedViews, allowed = _ != "forbidden")
      try {
        val guard = fixture.open()
        fixture.authorizeCalls.set(0)
        val result = guard.outputAdmission.get("fixed frame", produced, current)
        if (accepted) await(result) shouldBe "fixed frame"
        else {
          intercept[SessionAccessDenied](await(result))
          await(fixture.underlying.resources).ownedViews shouldBe 0
        }
        fixture.authorizeCalls.get() shouldBe 1
      } finally fixture.close()
    }
  }

  it should "reject changed captured authority in the same observation even when both presentations remain allowed" in {
    val fixture = new Fixture(ownership = BrowserOwnership.SharedViews)
    try {
      val guard = fixture.open()
      fixture.authorizeCalls.set(0)
      fixture.authorizeNewGeneration.set(true)
      intercept[SessionAccessDenied](await(guard.outputAdmission.get("fixed frame", "fresh", "fresh")))
      fixture.authorizeCalls.get() shouldBe 1
      await(fixture.underlying.resources).ownedViews shouldBe 0
      // Detachment prevents later output even if the provider's observation
      // returns to its earlier value; a new socket must acquire fresh authority.
      fixture.authorizeNewGeneration.set(false)
      intercept[SessionAccessDenied](await(guard.outputAdmission.get("later frame", "fresh", "fresh")))
      fixture.authorizeCalls.get() shouldBe 1
    } finally fixture.close()
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
    val queued  = f.browser.currentPrincipal(f.request())
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

  "Maintenance sweep" should "let other coordinator work interleave between per-view revalidations" in withFixture {
    f =>
      val (_, first)      = f.openOwned(Qsid("device", "first"))
      val (_, second)     = f.openOwned(Qsid("device", "second"))
      val (_, third)      = f.openOwned(Qsid("device", "third"))
      val (click, fourth) = f.openOwned(Qsid("device", "fourth"))
      val held            = Promise[Unit]()
      f.authorizeHold.set(Some(first -> held))
      f.authorizeLog.set(Vector.empty)
      val sweep = f.browser.maintenance()
      f.authorizeLog.get() shouldBe Vector(first)
      // Queued while the first revalidation is held; it must not wait for the
      // other views, which a single sweep turn would have checked first.
      val authorized = click.authorize("fresh")
      authorized.isCompleted shouldBe false
      held.success(())
      await(authorized)
      await(sweep)
      // The click ran before the remaining revalidations, which end with the
      // click's own view.
      f.authorizeLog.get() shouldBe Vector(first, fourth, second, third, fourth)
  }

  it should "skip a view closed after the sweep started" in withFixture { f =>
    val (_, first)      = f.openOwned(Qsid("device", "first"))
    val (second, other) = f.openOwned(Qsid("device", "second"))
    val held            = Promise[Unit]()
    f.authorizeHold.set(Some(first -> held))
    f.authorizeLog.set(Vector.empty)
    val sweep   = f.browser.maintenance()
    val closing = second.close()
    closing.isCompleted shouldBe false
    held.success(())
    await(closing)
    await(sweep)
    f.authorizeLog.get() shouldBe Vector(first)
    await(f.underlying.resources).ownedViews shouldBe 1
  }

  it should "end quietly when close is requested during the sweep" in withFixture { f =>
    val (_, first) = f.openOwned(Qsid("device", "first"))
    f.openOwned(Qsid("device", "second"))
    val held = Promise[Unit]()
    f.authorizeHold.set(Some(first -> held))
    f.authorizeLog.set(Vector.empty)
    val sweep   = f.browser.maintenance()
    val closing = f.browser.close()
    closing.isCompleted shouldBe false
    held.success(())
    await(sweep)
    await(closing)
    f.authorizeLog.get() shouldBe Vector(first)
  }

  "Concurrent checks" should "run checks of different bindings concurrently and serialize one binding" in {
    val f = new Fixture(concurrency = Some(BrowserCheckConcurrency(2)))
    try {
      val (first, a)  = f.openOwned(Qsid("device", "first"), binding = "a" * 43)
      val (second, _) = f.openOwned(Qsid("device", "second"), binding = "c" * 43)
      val (third, _)  = f.openOwned(Qsid("device", "third"), binding = "a" * 43)
      val held        = Promise[Unit]()
      f.authorizeHold.set(Some(a -> held))
      f.authorizeLog.set(Vector.empty)
      val one = first.authorize("fresh")
      one.isCompleted shouldBe false
      // Another binding's check runs beside the held one; the same binding's waits.
      await(second.authorize("fresh"))
      val more = third.authorize("fresh")
      more.isCompleted shouldBe false
      held.success(())
      await(one)
      await(more)
      f.authorizeLog.get().size shouldBe 3
      await(first.close()); await(second.close()); await(third.close())
    } finally f.close()
  }

  it should "deny a queued check of a view denied in a read turn without another observation" in {
    val f = new Fixture(concurrency = Some(BrowserCheckConcurrency(2)))
    try {
      val (guard, owner) = f.openOwned(f.viewKey)
      val old            = f.manager
      val held           = Promise[Unit]()
      f.authorizeHold.set(Some(owner -> held))
      f.authorizeLog.set(Vector.empty)
      val denied  = guard.authorize("fresh")
      val binding = new InvocationBinding(InvocationId.fromUuid(new UUID(0L, 99L)), owner,
        expectedAuthority = Some(f.browser.authority))
      val queued = f.browser.authority.resolve(binding)
      queued.isCompleted shouldBe false
      held.failure(f.authorizeError)
      await(denied.failed) shouldBe f.authorizeError
      await(queued) shouldBe Left(AccessDenied.StaleAuthority)
      f.authorizeLog.get() shouldBe Vector(owner)
      await(f.underlying.resources).ownedViews shouldBe 0
      intercept[BoundedPresentationStore.Rejected](await(old.read[String](Id.TopLevel)))
    } finally f.close()
  }

  it should "give a same-identity reconnect queued ahead of the deferred detach a fresh presentation" in {
    val f = new Fixture(concurrency = Some(BrowserCheckConcurrency(2)))
    try {
      val (guard, owner) = f.openOwned(f.viewKey)
      await(f.manager.write(Id("1_2"), "denied presentation"))
      val held = Promise[Unit]()
      f.authorizeHold.set(Some(owner -> held))
      val denied = guard.authorize("fresh")
      val reconnect = f.configured.sessionAccessControl.get
        .resume(f.viewKey, f.request(), ConnectionId.fromUuid(new UUID(0L, 77L))).get
      reconnect.isCompleted shouldBe false
      held.failure(f.authorizeError)
      await(denied.failed) shouldBe f.authorizeError
      val resumed = await(reconnect)
      resumed.applicationStateInitialization match {
        case ApplicationStateInitialization.Reconstructed(complete) => await(complete())
        case other => fail(s"Expected a fresh presentation, got $other")
      }
      await(f.manager.read[String](Id("1_2"))) shouldBe None
      await(resumed.close())
    } finally f.close()
  }

  it should "keep the presentation when the queue bound rejects a check" in {
    val f = new Fixture(concurrency = Some(BrowserCheckConcurrency(2)))
    try {
      val (guard, owner) = f.openOwned(f.viewKey)
      await(f.manager.write(Id("1_2"), "kept presentation"))
      val held = Promise[Unit]()
      f.authorizeHold.set(Some(owner -> held))
      val running = guard.authorize("fresh")
      // The queue bound is one: a second queued turn fills it and a third is rejected.
      val queued   = f.browser.localHistoryUsage
      val rejected = guard.authorize("fresh")
      intercept[IllegalStateException](await(rejected))
      held.success(())
      await(running)
      await(queued)
      val resumed = await(f.configured.sessionAccessControl.get
        .resume(f.viewKey, f.request(), ConnectionId.fromUuid(new UUID(0L, 78L))).get)
      resumed.applicationStateInitialization match {
        case ApplicationStateInitialization.Retained() => ()
        case other => fail(s"Expected the kept presentation, got $other")
      }
      await(f.manager.read[String](Id("1_2"))) shouldBe Some("kept presentation")
      await(resumed.close())
    } finally f.close()
  }

  it should "let close release a view whose deferred detach the gate rejected" in {
    val f = new Fixture(concurrency = Some(BrowserCheckConcurrency(2)))
    try {
      val (guard, owner) = f.openOwned(f.viewKey)
      val held           = Promise[Unit]()
      f.authorizeHold.set(Some(owner -> held))
      f.releaseCalls.set(0)
      val denied  = guard.authorize("fresh")
      val closing = f.browser.close()
      closing.isCompleted shouldBe false
      held.failure(f.authorizeError)
      await(denied.failed) shouldBe f.authorizeError
      await(closing)
      f.releaseCalls.get() shouldBe 1
    } finally f.close()
  }
}
