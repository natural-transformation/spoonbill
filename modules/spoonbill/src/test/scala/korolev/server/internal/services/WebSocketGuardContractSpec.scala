package spoonbill.server.internal.services

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.internal.BadRequestException
import spoonbill.server.{
  AuthenticationCompletionConfig,
  SessionAccessControl,
  SessionAccessDenied,
  SessionGuard,
  SpoonbillServiceConfig,
  StateLoader,
  WebSocketRequest,
  WebSocketResponse
}
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

final class WebSocketGuardContractSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  private final class Probe(authorizeResult: Future[Unit], openResult: Option[Future[SessionGuard[Future, String]]] = None)
      extends SessionAccessControl[Future, String] {
    val opened = new AtomicInteger(0)
    val closed = new AtomicInteger(0)
    val resumed = new AtomicInteger(0)
    def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
    def open(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): Future[SessionGuard[Future, String]] = {
      opened.incrementAndGet()
      openResult.getOrElse(Future.successful(guard))
    }
    override def resume(
      qsid: Qsid,
      request: Request.Head,
      connectionId: ConnectionId
    ): Option[Future[SessionGuard[Future, String]]] = {
      resumed.incrementAndGet()
      None
    }
    private def guard: SessionGuard[Future, String] = new SessionGuard[Future, String] {
      def authorize(state: String): Future[Unit] = authorizeResult
      def connected(state: String): Future[String] = authorizeResult.flatMap(_ => Future.successful(state))
      def close(): Future[Unit] = effect.delay { closed.incrementAndGet(); () }
    }
  }

  private def messaging(control: SessionAccessControl[Future, String]): (SessionsService[Future, String, Any], MessagingService[Future]) = {
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("initial"),
      sessionAccessControl = Some(control)
    )
    val sessions = new SessionsService[Future, String, Any](config, new PageService[Future, String, Any](config))
    val service = new MessagingService[Future](config.reporter, new CommonService[Future], sessions, None, 1.second)
    (sessions, service)
  }

  private val root = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())

  "Guarded setup" should "fail closed when authorization fails and release the guard" in {
    val control = new Probe(Future.failed(new SessionAccessDenied))
    val (sessions, service) = messaging(control)
    val qsid = Qsid("device", "denied")
    val incoming = Stream.endless[Future, Bytes]
    val result = for {
      _ <- sessions.initAppState(qsid, root)
      failure <- service.webSocketMessaging(qsid, root, incoming, Seq("json")).failed
      app <- sessions.getApp(qsid)
    } yield (failure, app)
    val (failure, app) = Await.result(result, 3.seconds)
    failure shouldBe a[SessionAccessDenied]
    app shouldBe None
    control.opened.get() shouldBe 1
    control.closed.get() shouldBe 1
  }

  it should "release a live session without delivering queued output when the response is discarded" in {
    val control = new Probe(Future.unit)
    val (sessions, service) = messaging(control)
    val qsid = Qsid("device", "attached")
    val incoming = Stream.endless[Future, Bytes]
    val result = for {
      _ <- sessions.initAppState(qsid, root)
      response <- service.webSocketMessaging(qsid, root, incoming, Seq("json"))
      duplex <- response match {
                  case value: WebSocketResponse.Duplex[Future] => Future.successful(value)
                  case WebSocketResponse.SendThenClose(_, _, _) =>
                    Future.failed(new AssertionError("A live view must stay duplex"))
                }
      _ <- duplex.release()
      pulled <- duplex.output.pull().recover { case _ => None }
      app <- sessions.getApp(qsid)
    } yield (pulled, app)
    val (pulled, app) = Await.result(result, 3.seconds)
    pulled shouldBe None
    app shouldBe None
    control.closed.get() shouldBe 1
  }

  it should "reject a disallowed origin without a successful socket result" in {
    val control = new Probe(Future.unit)
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("initial"),
      sessionAccessControl = Some(control),
      authenticationCompletion = Some(AuthenticationCompletionConfig[Future](
        bindingCookieName = "test-binding",
        sessionCookieName = "test-session",
        allowedOrigins = Set("http://localhost"),
        secureCookies = false,
        cookieMaxAgeSeconds = 60L,
        deliver = (_, _) => Future.successful(None),
        logout = (_, _) => Future.unit
      ))
    )
    val server = spoonbill.server.spoonbillService(config)
    val request = Request(
      Request.Method.Get,
      PathAndQuery.fromString("/bridge/web-socket/missing-view"),
      Seq("Origin" -> "http://evil.example", "Sec-Fetch-Site" -> "cross-site"),
      None,
      Stream.endless[Future, Bytes],
      renderedCookie = "deviceId=device"
    )
    val failure = Await.result(server.ws(WebSocketRequest(request, Seq("json"))).failed, 3.seconds)
    failure shouldBe a[BadRequestException]
    control.opened.get() shouldBe 0
    control.resumed.get() shouldBe 0
  }
}
