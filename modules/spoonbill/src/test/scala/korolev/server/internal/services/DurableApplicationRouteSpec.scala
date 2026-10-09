package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.{Qsid, Router}
import spoonbill.effect.{Effect, Queue}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.server.*
import spoonbill.snapshot.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

class DurableApplicationRouteSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private case class State(page: String, requestMarker: String) extends Serializable
  private val deepLink = PathAndQuery.fromString("/invoices/42?tab=details")

  private class Fixture(route: PathAndQuery = deepLink, page: String = "invoice-42-details") {
    val qsid = Qsid("route-device", UUID.randomUUID().toString)
    val http = Request(Request.Method.Get, route, Seq("X-Marker" -> "old-header"), None, ())
      .withCookie("marker", "old-cookie")
    val handshake = Request(Request.Method.Get,
      PathAndQuery.fromString(s"/bridge/web-socket/${qsid.sessionId}"), Seq("X-Marker" -> "new-header"), None, ())
      .withCookie("marker", "new-cookie")
    val saved = Promise[String]()
    val closed = Promise[Unit]()
    val loadedPath = Promise[PathAndQuery]()
    val loadedTab = Promise[Option[String]]()
    val store = new ViewSnapshotStore[Future, String] {
      val ownerEpoch = ViewOwnershipEpoch.initial
      def load() = Future.successful(Right(SnapshotLoad.Empty(ViewRevision.initial)))
      def save(expected: ViewRevision, value: String) = {
        saved.trySuccess(value)
        Future.successful(expected.next.left.map(_ => ViewSnapshotError.RevisionExhausted))
      }
      def reset(expected: ViewRevision, value: String) = Future.failed(new AssertionError("No reset expected"))
    }
    val control = new SessionAccessControl[Future, State] {
      def authorizeHttp(request: Request.Head, state: State) = Future.unit
      override def resume(id: Qsid, request: Request.Head, connection: ConnectionId) = Some(open(id, request, connection))
      def open(id: Qsid, request: Request.Head, connection: ConnectionId) = Future.successful(new SessionGuard[Future, State] {
        override val viewSnapshots = Some(ViewSnapshotSession.projected[Future, State, String](store,
          _.page, (fresh, page) => fresh.copy(page = page)))
        def authorize(state: State) = if (state.requestMarker == "new-cookie:new-header") Future.unit
          else Future.failed(new SessionAccessDenied)
        def connected(state: State) = Future.successful(state)
        def close() = Future.successful { closed.trySuccess(()); () }
      })
    }
    val router = Router[Future, State](fromState = PartialFunction.empty, toState = {
      case path if path == route => state => Future.successful(state.copy(page = page))
    })
    val config = SpoonbillServiceConfig[Future, State, Any](
      stateLoader = (_, request) => {
        if (request.cookie("marker").contains("new-cookie")) {
          loadedPath.trySuccess(request.pq)
          loadedTab.trySuccess(request.param("tab"))
        }
        Future.successful(State("home", request.cookie("marker").getOrElse("") + ":" + request.header("X-Marker").getOrElse("")))
      },
      router = router, document = state => Html(body(div(state.page), div(state.requestMarker))),
      sessionAccessControl = Some(control)
    )(executionContext)
    val service = new SessionsService(config, new PageService(config))

    def attach(request: Request.Head): Future[String] = for {
      _ <- service.createAppIfNeeded(qsid, request, Queue[Future, String]().stream)
      current <- service.getApp(qsid)
      app = current.getOrElse(fail("Missing durable app"))
      frames <- untilReady(app.frontend)
      _ <- app.frontend.close()
      _ <- closed.future
    } yield frames.find(_.startsWith("[19,")).getOrElse(fail("Missing full baseline"))

    private def untilReady(frontend: spoonbill.internal.Frontend[Future], remaining: Int = 8,
      seen: Vector[String] = Vector.empty): Future[Vector[String]] =
      if (remaining == 0) Future.failed(new AssertionError("No application readiness"))
      else frontend.outgoingMessages.pull().flatMap {
        case Some(frame) if frame.startsWith("[21,") => Future.successful(seen :+ frame)
        case Some(frame) => untilReady(frontend, remaining - 1, seen :+ frame)
        case None => Future.failed(new AssertionError("Closed before readiness"))
      }
  }

  "First durable attachment" should "keep the HTTP route while rebuilding state from current request authority" in {
    val fixture = new Fixture
    for {
      bootstrap <- fixture.service.initAppState(fixture.qsid, fixture.http)
      _ = bootstrap.page shouldBe "invoice-42-details"
      baseline <- fixture.attach(fixture.handshake)
      saved <- fixture.saved.future
      loaded <- fixture.loadedPath.future
    } yield {
      saved shouldBe "invoice-42-details"
      loaded shouldBe deepLink
      baseline should include("invoice-42-details")
      baseline should include("new-cookie:new-header")
      baseline should not include "old-cookie"
      baseline should not include "home"
    }
  }

  it should "route a resumed view using the current browser URI without a local bootstrap" in {
    val fixture = new Fixture
    for {
      baseline <- fixture.attach(fixture.handshake.withParam("__spoonbill_location", deepLink.mkString))
      saved <- fixture.saved.future
      loaded <- fixture.loadedPath.future
    } yield {
      loaded shouldBe deepLink
      saved shouldBe "invoice-42-details"
      baseline should include("invoice-42-details")
    }
  }

  it should "preserve root queries through HTTP bootstrap, fresh loading, routing and first persistence" in {
    val rootQuery = PathAndQuery.fromString("/?tab=details")
    val fixture = new Fixture(rootQuery, "root-details")
    for {
      plain <- fixture.service.initAppState(Qsid("route-device", UUID.randomUUID().toString),
        fixture.http.copy(pq = PathAndQuery.Root))
      _ = plain.page shouldBe "home"
      bootstrap <- fixture.service.initAppState(fixture.qsid, fixture.http)
      _ = bootstrap.page shouldBe "root-details"
      baseline <- fixture.attach(fixture.handshake)
      saved <- fixture.saved.future
      path <- fixture.loadedPath.future
      tab <- fixture.loadedTab.future
    } yield {
      path shouldBe rootQuery
      tab shouldBe Some("details")
      saved shouldBe "root-details"
      baseline should include("root-details")
    }
  }

  it should "accept an explicit absolute root query when resuming without a local bootstrap" in {
    val fixture = new Fixture(PathAndQuery.fromString("/?tab=details"), "root-details")
    for {
      baseline <- fixture.attach(fixture.handshake.withParam("__spoonbill_location", "/?tab=details"))
      tab <- fixture.loadedTab.future
    } yield {
      tab shouldBe Some("details")
      baseline should include("root-details")
    }
  }

  it should "use current browser navigation ahead of the original bootstrap URI" in {
    val fixture = new Fixture
    for {
      _ <- fixture.service.initAppState(fixture.qsid, fixture.http.copy(pq = PathAndQuery.Root))
      baseline <- fixture.attach(fixture.handshake.withParam("__spoonbill_location", deepLink.mkString))
    } yield baseline should include("invoice-42-details")
  }

  it should "reject an invalid application location before initializing persistence" in {
    val fixture = new Fixture
    fixture.service.createAppIfNeeded(fixture.qsid,
      fixture.handshake.withParam("__spoonbill_location", "//outside.invalid/invoices/42"), Queue[Future, String]().stream)
      .failed.map { error =>
        error shouldBe a[SessionAccessDenied]
        fixture.saved.isCompleted shouldBe false
      }
  }

  it should "still reject raw relative, protocol-relative and transport locations" in {
    Future.sequence(Vector("?tab=details", "invoices/42", "//outside.invalid/", "/bridge/web-socket/view").map { location =>
      val fixture = new Fixture
      fixture.service.createAppIfNeeded(fixture.qsid,
        fixture.handshake.withParam("__spoonbill_location", location), Queue[Future, String]().stream)
        .failed.map { error =>
          error shouldBe a[SessionAccessDenied]
          fixture.saved.isCompleted shouldBe false
        }
    }).map(_ => succeed)
  }
}
