package spoonbill.server.internal.services

import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import spoonbill.{Component, Context, Extension, Qsid, Router}
import spoonbill.auth.*
import spoonbill.effect.{Effect, Queue, Stream}
import spoonbill.internal.TimerTestSupport.{Reports, Worker}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{SessionAccessDenied, SpoonbillServiceConfig, StateLoader}
import spoonbill.state.{StateManager, StateStorage}
import spoonbill.state.javaSerialization._
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

class SessionsServiceSpec extends AnyFlatSpec with Matchers {

  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  private final class AnonymousFixture {
    val qsid          = Qsid("anonymous-device", UUID.randomUUID().toString)
    val time          = new AtomicReference(Instant.parse("2026-10-17T00:00:00Z"))
    val loaded        = new AtomicInteger()
    val failLoad      = new java.util.concurrent.atomic.AtomicBoolean()
    val configuration = BrowserConfiguration(BrowserProviderScope("route-test", "browser"), Set("https://app.example"))
    val provider = new InMemoryBrowserPersistence[Future, Unit, String](
      (),
      new InMemoryBrowserPersistence.Host[Unit, String] {
        def resolve(state: Unit, sessionId: UUID, now: Instant): Option[HostPrincipal[String]] = None
      },
      () => time.get(),
      () => UUID.randomUUID(),
      scope = configuration.scope
    )
    val browser = new BrowserAuthentication[Future, String, String](
      provider,
      BrowserPresentation(() => "landing", (state, _) => !state.startsWith("private"), (state, _, _) => state),
      configuration,
      clock = () => time.get()
    )
    val config = browser.configure(
      SpoonbillServiceConfig[Future, String, Any](
        stateLoader = (_, request) => {
          loaded.incrementAndGet()
          if (failLoad.get()) Future.failed(new IllegalStateException("Synthetic loader failure"))
          else Future.successful("loaded:" + request.header("X-Marker").getOrElse("missing"))
        },
        router = Router[Future, String](
          PartialFunction.empty,
          {
            case path if path.asPath.mkString == "/login" =>
              state => Future.successful("login:" + path.param("continue").getOrElse("") + ":" + state)
            case path if path.asPath.mkString == "/help"    => state => Future.successful("help:" + state)
            case path if path.asPath.mkString == "/private" => _ => Future.successful("private")
          }
        ),
        document = state => Html(body(div(state)))
      )
    )
    val service = new SessionsService(config, new PageService(config))
    def request(path: String, marker: String = "current", origin: String = "https://app.example") =
      Request(
        Request.Method.Get,
        PathAndQuery.fromString(path),
        Seq("Origin" -> origin, "X-Marker" -> marker),
        None,
        ()
      )
        .withCookie(configuration.bindingCookieName, "b" * 43)
    def handshake(location: String) =
      request(s"/bridge/web-socket/${qsid.sessionId}").withParam("__spoonbill_location", location)
    def seed(path: String): Unit =
      await(service.initAppState(qsid, request(path, "http")))
    def losePresentation(): Unit = {
      val guard = await(
        config.sessionAccessControl.get.open(qsid, request("/"), ConnectionId.fromUuid(UUID.randomUUID()))
      )
      await(guard.close())
      time.set(time.get().plusSeconds(121))
      await(config.stateStorage.exists(qsid.deviceId, qsid.sessionId)) shouldBe false
    }
    def attach(location: String): SessionStart.Attached[Future] =
      await(service.createAppIfNeeded(qsid, handshake(location), Stream.endless[Future, String])) match {
        case attached: SessionStart.Attached[Future] => attached
        case _                                       => fail("Expected the registered anonymous view to attach")
      }
    def ready(attached: SessionStart.Attached[Future]): Vector[String] = {
      var frames = Vector.empty[String]
      while (frames.size < 16 && !frames.lastOption.exists(_.startsWith("[21,"))) {
        frames :+= await(attached.outgoing.pull()).getOrElse(fail("Closed before readiness"))
      }
      frames.last should startWith("[21,")
      frames
    }
    def state: String = await(
      await(config.stateStorage.get(qsid.deviceId, qsid.sessionId)).read[String](Id.TopLevel)
    ).get
    def close(): Unit = await(browser.close())
  }

  private def withAnonymous(run: AnonymousFixture => Unit): Unit = {
    val fixture = new AnonymousFixture
    try run(fixture)
    finally fixture.close()
  }

  "createAppIfNeeded" should "rebuild missing state and create app" in {
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("initial-state")
    )
    val pageService     = new PageService[Future, String, Any](config)
    val sessionsService = new SessionsService[Future, String, Any](config, pageService)

    val qsid           = Qsid("device", "session")
    val request        = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incomingStream = Stream.endless[Future, String]

    val resultF =
      sessionsService
        .createAppIfNeeded(qsid, request, incomingStream)
        .flatMap(_ => sessionsService.getApp(qsid))

    val result = Await.result(resultF, 3.seconds)
    result.isDefined shouldBe true
  }

  it should "reconstruct an anonymous login route and continuation before the full DOM baseline and readiness" in withAnonymous {
    f =>
      f.seed("/login?continue=account")
      f.losePresentation()
      val attached = f.attach("/login?continue=account")
      try {
        val frames = f.ready(attached)
        f.state shouldBe "login:account:loaded:current"
        f.loaded.get() shouldBe 2
        val baseline = frames.find(_.startsWith("[19,")).getOrElse(fail("Missing reconstructed DOM baseline"))
        baseline should include("login:account:loaded:current")
        baseline should not include "landing"
        baseline should not include "loaded:http"
      } finally await(attached.release())
  }

  it should "reconstruct a non-login public route after anonymous presentation loss" in withAnonymous { f =>
    f.seed("/help")
    f.losePresentation()
    val attached = f.attach("/help")
    try {
      f.ready(attached).find(_.startsWith("[19,")).get should include("help:loaded:current")
      f.state shouldBe "help:loaded:current"
    } finally await(attached.release())
  }

  it should "preserve seeded and changed retained anonymous presentation without running the loader again" in {
    Seq(false, true).foreach { reconstruct =>
      withAnonymous { f =>
        f.seed("/login?continue=account")
        if (reconstruct) f.losePresentation()
        val first = f.attach("/login?continue=account")
        f.ready(first)
        val expectedLoads = if (reconstruct) 2 else 1
        f.loaded.get() shouldBe expectedLoads
        f.state shouldBe s"login:account:loaded:${if (reconstruct) "current" else "http"}"
        val app = await(f.service.getApp(f.qsid)).get
        await(app.topLevelComponentInstance.browserAccess.transitionForce(_ => "changed public presentation"))
        await(first.release())
        val second = f.attach("/help")
        try {
          f.ready(second)
          f.state shouldBe "changed public presentation"
          f.loaded.get() shouldBe expectedLoads
        } finally await(second.release())
      }
    }
  }

  it should "reject invalid cold anonymous paths before invoking the loader" in {
    Seq("//outside.example/login", "login", "/bridge/web-socket/view", "/login#fragment").foreach { path =>
      withAnonymous { f =>
        f.seed("/login")
        f.losePresentation()
        intercept[SessionAccessDenied](f.attach(path))
        f.loaded.get() shouldBe 1
        await(f.service.getApp(f.qsid)) shouldBe None
      }
    }
  }

  it should "deny a reconstructed private route before publishing an application" in withAnonymous { f =>
    f.seed("/login")
    f.losePresentation()
    intercept[SessionAccessDenied](f.attach("/private"))
    f.loaded.get() shouldBe 2
    await(f.service.getApp(f.qsid)) shouldBe None
    await(f.provider.resources).ownedViews shouldBe 0
  }

  it should "retry anonymous route loading after a rejected path or failed loader without retaining the placeholder" in {
    Seq(false, true).foreach { loaderFailure =>
      withAnonymous { f =>
        f.seed("/login?continue=account")
        f.losePresentation()
        if (loaderFailure) {
          f.failLoad.set(true)
          intercept[IllegalStateException](f.attach("/login?continue=account"))
          f.failLoad.set(false)
        } else intercept[SessionAccessDenied](f.attach("//outside.example/login"))
        await(f.config.stateStorage.exists(f.qsid.deviceId, f.qsid.sessionId)) shouldBe false
        val attached = f.attach("/login?continue=account")
        try {
          f.ready(attached).find(_.startsWith("[19,")).get should include("login:account:loaded:current")
          f.state shouldBe "login:account:loaded:current"
          f.loaded.get() shouldBe (if (loaderFailure) 3 else 2)
        } finally await(attached.release())
      }
    }
  }

  it should "reject unknown views and foreign origins before cold anonymous loading" in withAnonymous { f =>
    intercept[SessionAccessDenied](f.attach("/login"))
    f.loaded.get() shouldBe 0
    f.seed("/login")
    f.losePresentation()
    intercept[SessionAccessDenied](
      await(
        f.service.createAppIfNeeded(
          f.qsid,
          f.handshake("/login").copy(headers = Seq("Origin" -> "https://outside.example")),
          Stream.endless[Future, String]
        )
      )
    )
    f.loaded.get() shouldBe 1
    await(f.provider.resources).ownedViews shouldBe 0
  }

  it should "retire a legacy session and reconnect after component cleanup and extension failures" in {
    val worker                        = new Worker
    val reports                       = new Reports
    val deleteFailure                 = new IllegalStateException("Synthetic component delete failure")
    var destroyed                     = Vector.empty[String]
    var removals                      = 0
    var manager: StateManager[Future] = null
    val storage = new StateStorage[Future, Int] {
      def exists(deviceId: String, sessionId: String): Future[Boolean] =
        Future.successful(removals == 0 && manager != null)
      def create(deviceId: String, sessionId: String, state: Int): Future[StateManager[Future]] = {
        manager = StateManager.cached[Future](Map(Id.TopLevel -> state), onDelete = _ => Future.failed(deleteFailure))
        Future.successful(manager)
      }
      def get(deviceId: String, sessionId: String): Future[StateManager[Future]] = Future.successful(manager)
      def remove(deviceId: String, sessionId: String): Unit                      = removals += 1
    }
    val context = Context[Future, Int, Any]
    import context.*
    val child = Component[Future, Int, Unit, Any](10)((_, _, state) => div(state.toString))
    val config = SpoonbillServiceConfig[Future, Int, Any](
      stateStorage = storage,
      stateLoader = StateLoader.default(0),
      document = state => Html(body(if (state == 0) child(()) else div("removed"))),
      reporter = reports,
      extensions = List(
        Extension.pure(_ =>
          Extension.Handlers[Future, Int, Any](onDestroy = () => {
            destroyed :+= "throwing"; throw new IllegalStateException("Synthetic extension failure")
          })
        ),
        Extension.pure(_ =>
          Extension.Handlers[Future, Int, Any](onDestroy = () => {
            destroyed :+= "following"; Future.unit
          })
        )
      )
    )(worker)
    val service = new SessionsService(config, new PageService(config))
    val qsid    = Qsid("legacy-cleanup", UUID.randomUUID().toString)
    val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    def attach(): SessionStart.Attached[Future] = {
      val attached = await(service.createAppIfNeeded(qsid, request, Queue[Future, String]().stream))
        .asInstanceOf[SessionStart.Attached[Future]]
      worker.drain()
      attached
    }
    val first = attach()
    val old   = await(service.getApp(qsid)).get
    await(old.topLevelComponentInstance.browserAccess.transition(_ => 1))
    worker.drain()
    reports.errors.map(_._2) should contain(deleteFailure)
    await(first.release())
    worker.drain()
    destroyed shouldBe Vector("throwing", "following")
    removals shouldBe 1
    await(service.getApp(qsid)) shouldBe None
    reports.errors should contain("Legacy session cleanup failed" -> deleteFailure)
    val second = attach()
    await(service.getApp(qsid)).get should not be theSameInstanceAs(old)
    await(second.outgoing.pull()).isDefined shouldBe true
    await(second.release())
    worker.drain()
  }
}
