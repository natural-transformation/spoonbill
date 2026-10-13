package spoonbill.auth

import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.action.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.server.*
import spoonbill.state.{BoundedPresentationStore, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

object BrowserAuthenticationSpec {
  final case class Page(
    label: String = "fresh",
    protectedPage: Boolean = false,
    user: Option[String] = None,
    continuation: Option[BrowserContinuation] = None
  )
}

/**
 * Test coordinator wiring and authority/presentation transitions independently
 * of the provider's own transaction conformance tests.
 */
final class BrowserAuthenticationSpec extends AnyFlatSpec with Matchers {
  import BrowserAuthenticationSpec.*
  private given Effect[Future]                    = Effect.futureEffect
  private val origin                              = "https://app.example"
  private val start                               = Instant.parse("2026-10-11T00:00:00Z")
  private val browserBinding                      = "b" * 43
  private val qsid                                = Qsid("device", "view")
  private val child                               = Id("1_2")
  private def await[A](value: Future[A]): A       = Await.result(value, 5.seconds)
  private def right[E, A](value: Either[E, A]): A = value.fold(error => fail(s"Expected success, got $error"), identity)

  private final class Fixture {
    private val ids                = new AtomicLong()
    def id(): UUID                 = new UUID(0L, ids.incrementAndGet())
    def connection(): ConnectionId = ConnectionId.fromUuid(id())
    val time                       = new AtomicReference(start)
    val freshPages                 = new AtomicInteger()
    val configuration              = BrowserConfiguration(BrowserProviderScope("test", "browser"), Set(origin))
    val provider = new InMemoryBrowserPersistence[Future, Map[UUID, HostPrincipal[String]], String](
      Map.empty,
      new InMemoryBrowserPersistence.Host[Map[UUID, HostPrincipal[String]], String] {
        def resolve(
          state: Map[UUID, HostPrincipal[String]],
          sessionId: UUID,
          now: Instant
        ): Option[HostPrincipal[String]] =
          state.get(sessionId).filter(value => now.isBefore(value.expiresAt))
      },
      () => time.get(),
      () => id(),
      scope = configuration.scope
    )
    val browser = new BrowserAuthentication[Future, Page, String](
      provider,
      BrowserPresentation(
        () => { freshPages.incrementAndGet(); Page() },
        (page, principal) => !page.protectedPage || principal.nonEmpty,
        (page, principal, continuation) => page.copy(user = principal, continuation = continuation)
      ),
      configuration,
      clock = () => time.get()
    )
    val base = SpoonbillServiceConfig[Future, Page, Any](
      stateLoader = StateLoader.default(Page()),
      document = page => Html(body(div(page.label)))
    )
    val configured = browser.configure(base)
    val access     = configured.sessionAccessControl.get

    def request(
      token: Option[String] = None,
      binding: Option[String] = Some(browserBinding),
      method: Request.Method = Request.Method.Get,
      headers: Seq[(String, String)] = Seq("Origin" -> origin)
    ): HttpRequest[Future] = {
      val cookies = binding.toSeq.map(configuration.bindingCookieName + "=" + _) ++
        token.toSeq.map(configuration.sessionCookieName + "=" + _)
      Request(method, PathAndQuery.Root, headers, None, Stream.empty[Future, Bytes], cookies.mkString("; "))
    }
    def open(owner: ConnectionId, token: Option[String] = None): SessionGuard[Future, Page] =
      await(access.open(qsid, request(token), owner))
    def manager = await(configured.stateStorage.get(qsid.deviceId, qsid.sessionId))
    def binding(owner: ConnectionId, expected: AnyRef = browser.authority): InvocationBinding =
      new InvocationBinding(InvocationId.fromUuid(id()), owner, expectedAuthority = Some(expected))
    def context(owner: ConnectionId): PublicActionContext = {
      val captured = Promise[PublicActionContext]()
      val action = new Actions[Future, Page, String]
        .public(right(ActionName.parse("test.context")), InputSchema.empty, PublicPolicy.allow[Future, Unit]) {
          (_, context) =>
            captured.success(context)
            Future.successful(UiOutcome.unchanged[Page])
        }
      await(ActionDispatcher.public(action, Vector.empty, binding(owner))) match {
        case InvocationResult.Completed(UiOutcome.Updated(_)) => ()
        case other                                            => fail(s"The public action did not dispatch: $other")
      }
      await(captured.future)
    }
    def prepare(owner: ConnectionId, user: String = "alice", rawToken: String = "t" * 43): CommittedAuthentication = {
      val ceremony = right(await(browser.begin(context(owner))))
      right(await(provider.prepare(ceremony) { transaction =>
        val session = id()
        val expiry  = time.get().plusSeconds(3600)
        transaction.modify(_ + (session -> HostPrincipal(user, user, 0L, 0L, expiry)))
        new HostSessionPrepared(session, id(), expiry, right(BrowserSessionToken.fromString(rawToken)))
      }))
    }
    def close(): Unit = await(browser.close())
  }

  private def withFixture(run: Fixture => Unit): Unit = {
    val fixture = new Fixture
    try run(fixture)
    finally fixture.close()
  }

  "Browser authentication installation" should "reject partial overrides before creating a service" in withFixture {
    f =>
      val other = new Fixture
      try {
        val invalid = Seq(
          f.configured.copy(sessionAccessControl = None),
          f.configured.copy(sessionAccessControl = other.configured.sessionAccessControl),
          f.configured.copy(authenticationCompletion = None),
          f.configured.copy(authenticationCompletion = other.configured.authenticationCompletion),
          f.configured.copy(stateStorage = other.configured.stateStorage),
          f.configured.copy(browserSecurityInstallation = None),
          f.configured.copy(webSocketEnabled = false),
          f.configured.copy(webSocketCompressionEnabled = true)
        )
        invalid.foreach(config => intercept[IllegalArgumentException](spoonbillService(config)))
        intercept[IllegalArgumentException](f.browser.configure(f.configured))
        intercept[IllegalArgumentException](
          f.browser.configure(f.base.copy(stateStorage = StateStorage.ephemeral[Future, Page]()))
        )
        await(f.provider.resources).ownedViews shouldBe 0
      } finally other.close()
  }

  it should "supply the newly issued binding to the initial HTTP loader and mandatory guard" in withFixture { f =>
    val seen           = new AtomicReference[Option[String]](None)
    val seenCredential = new AtomicReference[Option[String]](None)
    val loader: StateLoader[Future, Page] = (_, request) => {
      seen.set(request.cookie(f.configuration.bindingCookieName))
      seenCredential.set(request.cookie(f.configuration.sessionCookieName))
      f.browser.currentPrincipal(request).map(result => Page(label = "initial public page", user = right(result)))
    }
    val service  = spoonbillService(f.configured.copy(stateLoader = loader))
    val response = await(service.http(f.request(token = Some("s" * 43), binding = None)))
    response.status.code shouldBe 200
    val cookie = response.headers.collectFirst {
      case ("Set-Cookie", value) if value.startsWith(f.configuration.bindingCookieName + "=") =>
        value.drop(f.configuration.bindingCookieName.length + 1).takeWhile(_ != ';')
    }.getOrElse(fail("The initial page did not establish a browser binding"))
    cookie should fullyMatch regex "[A-Za-z0-9_-]{43}"
    seen.get() shouldBe Some(cookie)
    seenCredential.get() shouldBe None
    await(response.body.fold(Bytes.empty)(_ ++ _)).asUtf8String should include("initial public page")
    await(f.provider.resources).bindings shouldBe 1
  }

  "Browser authentication authority" should "enforce public/protected presentation and default-deny sensitive disclosure" in withFixture {
    f =>
      val owner = f.connection()
      val guard = f.open(owner)
      await(guard.authorize(Page()))
      guard.sensitive shouldBe None
      guard.viewSnapshots shouldBe None
      guard.actionAuthority shouldBe Some(f.browser.authority)
      await(f.browser.authority.resolve(f.binding(owner))) shouldBe Left(AccessDenied.Unauthenticated)
      await(f.access.authorizeHttp(f.request(), Page(label = "forged", user = Some("alice"))))
      intercept[SessionAccessDenied](
        await(f.access.authorizeHttp(f.request(), Page(protectedPage = true, user = Some("alice"))))
      )
      intercept[SessionAccessDenied](await(guard.authorize(Page(protectedPage = true, user = Some("alice")))))
      await(f.provider.resources).ownedViews shouldBe 0
      await(guard.close())
  }

  it should "reject an authenticated action's foreign authority before running its handler" in withFixture { f =>
    val owner    = f.connection()
    val guard    = f.open(owner)
    val invoked  = new AtomicInteger()
    val resolved = new AtomicInteger()
    val foreign = new SessionAuthority[Future, String] {
      def resolve(binding: InvocationBinding)                       = { resolved.incrementAndGet(); Future.successful(Right("forged")) }
      def revalidate(binding: InvocationBinding, principal: String) = Future.successful(AccessDecision.Allowed)
    }
    val action = new Actions[Future, Page, String].authenticated(
      right(ActionName.parse("test.protected")),
      InputSchema.empty,
      ActionPolicy[Future, Unit, String]((_, _) => Future.successful(AccessDecision.Allowed))
    ) { (_, _) =>
      invoked.incrementAndGet()
      Future.successful(UiOutcome.unchanged[Page])
    }
    await(ActionDispatcher.authenticated(action, Vector.empty, f.binding(owner), foreign)) shouldBe
      InvocationResult.Rejected(ActionRejection.Access(AccessDenied.StaleAuthority))
    resolved.get() shouldBe 0
    invoked.get() shouldBe 0
    await(guard.close())
  }

  it should "keep the old socket anonymous through delivery and replace all nodes on the authenticated handshake" in withFixture {
    f =>
      await(f.configured.stateStorage.create(qsid.deviceId, qsid.sessionId, Page()))
      val oldOwner     = f.connection()
      val oldGuard     = f.open(oldOwner)
      val staleManager = f.manager
      oldGuard.ownershipEpoch shouldBe None
      await(staleManager.write(Id.TopLevel, Page(label = "anonymous setup with an extra error banner")))
      await(staleManager.write(child, "anonymous component must not survive login"))
      val receipt = f.prepare(oldOwner)
      f.browser.complete(receipt) shouldBe UiOutcome.CommitAuthentication(receipt.completionId)
      val delivered = await(f.configured.authenticationCompletion.get.deliver(receipt.completionId, browserBinding)).get
      delivered.value shouldBe "t" * 43
      await(f.browser.authority.resolve(f.binding(oldOwner))) shouldBe Left(AccessDenied.Unauthenticated)
      await(oldGuard.close())
      val newOwner = f.connection()
      val newGuard = f.open(newOwner, Some(delivered.value))
      newGuard.ownershipEpoch shouldBe Some(right(await(f.browser.currentView(f.context(newOwner)))).fence.epoch)
      newGuard.viewSnapshots shouldBe None
      await(f.manager.read[String](child)) shouldBe None
      await(f.manager.read[Page](Id.TopLevel)) shouldBe Some(Page())
      await(newGuard.connected(Page())).user shouldBe Some("alice")
      await(newGuard.authorize(Page(protectedPage = true)))
      await(f.access.authorizeHttp(f.request(Some(delivered.value)), Page(protectedPage = true)))
      await(f.browser.authority.resolve(f.binding(newOwner))) shouldBe Right("alice")
      intercept[BoundedPresentationStore.Rejected](await(staleManager.write(child, "late")))
      await(oldGuard.close())
      await(newGuard.authorize(Page(protectedPage = true)))
      f.freshPages.get() shouldBe 1
      val authenticatedPage = Page(label = "recovery codes", protectedPage = true, user = Some("alice"))
      await(f.manager.write(Id.TopLevel, authenticatedPage))
      await(f.manager.write(child, "authenticated component"))
      await(newGuard.close())
      val resumed = await(f.access.resume(qsid, f.request(Some(delivered.value)), f.connection()).get)
      resumed.ownershipEpoch should not be empty
      resumed.ownershipEpoch.get.toLong should be > newGuard.ownershipEpoch.get.toLong
      await(f.manager.read[Page](Id.TopLevel)) shouldBe Some(authenticatedPage)
      await(f.manager.read[String](child)) shouldBe Some("authenticated component")
      await(resumed.authorize(authenticatedPage))
      f.freshPages.get() shouldBe 1
      await(resumed.close())
  }

  it should "retain component nodes for an ordinary same-identity reconnect and fence old ownership" in withFixture {
    f =>
      await(f.configured.stateStorage.create(qsid.deviceId, qsid.sessionId, Page()))
      val firstOwner = f.connection()
      val first      = f.open(firstOwner)
      val old        = f.manager
      first.ownershipEpoch shouldBe None
      await(old.write(child, "retained component"))
      await(first.close())
      val second = await(f.access.resume(qsid, f.request(), f.connection()).get)
      second.ownershipEpoch shouldBe None
      await(f.manager.read[String](child)) shouldBe Some("retained component")
      intercept[BoundedPresentationStore.Rejected](await(old.read[String](child)))
      await(first.close())
      await(second.authorize(Page()))
      f.freshPages.get() shouldBe 0
      await(second.close())
  }

  it should "require a baseline when a handshake has no HTTP presentation to adopt" in withFixture { f =>
    val owner = f.connection()
    val guard = f.open(owner)
    guard.ownershipEpoch shouldBe Some(right(await(f.browser.currentView(f.context(owner)))).fence.epoch)
    await(f.manager.read[Page](Id.TopLevel)) shouldBe Some(Page())
    f.freshPages.get() shouldBe 1
    await(guard.close())
    val resumed = await(f.access.resume(qsid, f.request(), f.connection()).get)
    resumed.ownershipEpoch should not be empty
    f.freshPages.get() shouldBe 1
    await(resumed.close())
  }

  it should "require a new baseline after same-authority disconnected presentation expires" in withFixture { f =>
    await(f.configured.stateStorage.create(qsid.deviceId, qsid.sessionId, Page()))
    val first = f.open(f.connection())
    first.ownershipEpoch shouldBe None
    await(f.manager.write(Id.TopLevel, Page(label = "anonymous setup with an extra error banner")))
    await(f.manager.write(child, "expired component"))
    await(first.close())
    f.time.set(start.plusSeconds(BoundedPresentationStore.Limits().reconnectSeconds + 1))
    val resumed = await(f.access.resume(qsid, f.request(), f.connection()).get)
    resumed.ownershipEpoch should not be empty
    await(f.manager.read[Page](Id.TopLevel)) shouldBe Some(Page())
    await(f.manager.read[String](child)) shouldBe None
    f.freshPages.get() shouldBe 1
    await(resumed.close())
    val retry = await(f.access.resume(qsid, f.request(), f.connection()).get)
    retry.ownershipEpoch should not be empty
    f.freshPages.get() shouldBe 1
    await(retry.close())
  }

  it should "suppress authenticated output when logout occurs during an awaited handler" in withFixture { f =>
    val initialOwner = f.connection()
    val initial      = f.open(initialOwner)
    val receipt      = f.prepare(initialOwner)
    val token        = await(f.configured.authenticationCompletion.get.deliver(receipt.completionId, browserBinding)).get.value
    await(initial.close())
    val owner   = f.connection()
    val guard   = f.open(owner, Some(token))
    val started = Promise[Unit]()
    val finish  = Promise[UiOutcome[Page]]()
    val action = new Actions[Future, Page, String].authenticated(
      right(ActionName.parse("test.awaited")),
      InputSchema.empty,
      ActionPolicy[Future, Unit, String]((_, _) => Future.successful(AccessDecision.Allowed))
    ) { (_, context) =>
      context.principal shouldBe "alice"
      started.success(())
      finish.future
    }
    val result = ActionDispatcher.authenticated(action, Vector.empty, f.binding(owner), f.browser.authority)
    await(started.future)
    await(f.configured.authenticationCompletion.get.logout(browserBinding, Some(token)))
    finish.success(UiOutcome.update[Page](_.copy(label = "must not publish")))
    await(result) shouldBe InvocationResult.OutputSuppressed(AccessDenied.StaleAuthority)
    await(guard.close())
  }

  it should "fence a still-open predecessor on takeover without letting its late close erase the successor" in withFixture {
    f =>
      val firstOwner = f.connection()
      val first      = f.open(firstOwner)
      val old        = f.manager
      await(old.write(child, "kept on takeover"))
      val second = f.open(f.connection())
      intercept[SessionAccessDenied](await(first.authorize(Page())))
      await(first.close())
      await(f.manager.read[String](child)) shouldBe Some("kept on takeover")
      await(second.authorize(Page()))
      await(f.provider.resources).ownedViews shouldBe 1
      await(second.close())
  }

  it should "invalidate logged-out presentation and build fresh anonymous state on the next handshake" in withFixture {
    f =>
      val anonymousOwner = f.connection()
      val anonymous      = f.open(anonymousOwner)
      val receipt        = f.prepare(anonymousOwner)
      val token =
        await(f.configured.authenticationCompletion.get.deliver(receipt.completionId, browserBinding)).get.value
      await(anonymous.close())
      val authenticatedOwner = f.connection()
      val authenticated      = f.open(authenticatedOwner, Some(token))
      val old                = f.manager
      await(old.write(child, "authenticated-only component"))
      await(f.configured.authenticationCompletion.get.logout(browserBinding, Some(token)))
      await(f.provider.resources).ownedViews shouldBe 0
      intercept[SessionAccessDenied](await(authenticated.authorize(Page())))
      intercept[BoundedPresentationStore.Rejected](await(old.read[String](child)))
      await(authenticated.close())
      val next = f.open(f.connection())
      next.ownershipEpoch should not be empty
      await(next.connected(Page())).user shouldBe None
      await(f.manager.read[String](child)) shouldBe None
      await(f.manager.read[Page](Id.TopLevel)) shouldBe Some(Page())
      intercept[SessionAccessDenied](await(f.access.authorizeHttp(f.request(Some(token)), Page(protectedPage = true))))
      await(next.close())
  }

  it should "reject unknown view resume and bad origins without allocating ownership" in withFixture { f =>
    val unknown = Qsid("device", "unknown")
    intercept[SessionAccessDenied](await(f.access.resume(unknown, f.request(), f.connection()).get))
    await(f.provider.resources).views shouldBe 0
    intercept[SessionAccessDenied](
      await(f.access.open(qsid, f.request(headers = Seq("Origin" -> "https://other.example")), f.connection()))
    )
    await(f.provider.resources).views shouldBe 0
  }

  it should "reject foreign completion receipts and closed provider receipts" in withFixture { f =>
    val owner   = f.connection()
    val guard   = f.open(owner)
    val receipt = f.prepare(owner)
    val other   = new Fixture
    try intercept[IllegalArgumentException](other.browser.complete(receipt))
    finally other.close()
    intercept[IllegalArgumentException](f.browser.complete(new BrowserCapabilityScope[String].committed(f.id())))
    await(guard.close())
    f.close()
    intercept[IllegalArgumentException](f.browser.complete(receipt))
  }

  it should "close idempotently, erase owned resources, and reject subsequent use" in withFixture { f =>
    val owner = f.connection()
    val guard = f.open(owner)
    val old   = f.manager
    await(old.write(child, "must be released"))
    val context = f.context(owner)
    f.prepare(owner)
    f.close()
    f.close()
    await(guard.close())
    await(f.provider.resources) shouldBe InMemoryBrowserPersistence.Resources(0, 0, 0, 0, 0, 0)
    intercept[BoundedPresentationStore.Rejected](await(old.read[String](child)))
    intercept[Exception](await(f.access.open(qsid, f.request(), f.connection())))
    intercept[Exception](await(f.browser.begin(context)))
  }

  it should "let the logout endpoint clear a stale memory-restart credential before public bootstrap" in withFixture {
    f =>
      val staleToken = "t" * 43
      await(f.browser.currentPrincipal(f.request(Some(staleToken)))).isLeft shouldBe true
      await(f.provider.resources).bindings shouldBe 0
      val endpoint = new spoonbill.server.internal.services.AuthenticationCompletionService[Future](
        f.configured.authenticationCompletion.get
      )
      val response = await(endpoint.logout(f.request(Some(staleToken), method = Request.Method.Post)))
      response.status.code shouldBe 303
      response.headers.exists { case (name, value) =>
        name == "Set-Cookie" && value.startsWith(f.configuration.sessionCookieName + "=;") && value.contains(
          "Max-Age=0"
        )
      } shouldBe true
      await(f.provider.resources).bindings shouldBe 0
      await(f.browser.currentPrincipal(f.request())) shouldBe Right(None)
      await(f.provider.resources).bindings shouldBe 1
      await(f.access.authorizeHttp(f.request(), Page()))
  }
}
