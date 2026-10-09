package spoonbill.akka

import java.util.concurrent.atomic.AtomicInteger
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Cookie, RawHeader}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, WebSocketRequest}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{AuthenticationCompletionConfig, SessionAccessControl, SessionGuard, SpoonbillServiceConfig, StateLoader}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*
import spoonbill.web.Request

/** Same missing-view contract as the Pekko adapter, over Akka HTTP. */
final class AkkaMissingLocalViewReloadSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("akka-missing-local-view-reload-spec")
  private implicit val materializer: Materializer = Materializer(system)
  private implicit val ec: ExecutionContext = system.dispatcher
  private val timeout = 10.seconds

  override def afterAll(): Unit =
    try Await.result(system.terminate(), timeout)
    finally super.afterAll()

  private final class NoResumeControl extends SessionAccessControl[Future, String] {
    val opened = new AtomicInteger(0)
    val resumed = new AtomicInteger(0)
    def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
    def open(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): Future[SessionGuard[Future, String]] = {
      opened.incrementAndGet()
      Future.failed(new IllegalStateException("An absent local view must not open a guard"))
    }
    override def resume(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): Option[Future[SessionGuard[Future, String]]] = {
      resumed.incrementAndGet()
      None
    }
  }

  private def decode(message: Message): Future[String] = message match {
    case TextMessage.Strict(text) => Future.successful(text)
    case TextMessage.Streamed(parts) => parts.runFold("")(_ + _)
    case BinaryMessage.Strict(bytes) => Future.successful(bytes.utf8String)
    case BinaryMessage.Streamed(parts) => parts.runFold(ByteString.empty)(_ ++ _).map(_.utf8String)
  }

  private def expectReload(control: Option[NoResumeControl], wsLoggingEnabled: Boolean = false): Unit = {
    val storage = StateStorage.ephemeral[Future, String](16)
    val rebuilds = new AtomicInteger(0)
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader[Future, String] { (_, _) =>
        rebuilds.incrementAndGet()
        Future.failed(new IllegalStateException("Synthetic view cannot be rebuilt"))
      },
      stateStorage = storage,
      sessionAccessControl = control,
      authenticationCompletion = control.map(_ => AuthenticationCompletionConfig[Future](
        bindingCookieName = "test-binding",
        sessionCookieName = "test-session",
        allowedOrigins = Set("http://localhost"),
        secureCookies = false,
        cookieMaxAgeSeconds = 60L,
        deliver = (_, _) => Future.successful(None),
        logout = (_, _) => Future.unit
      ))
    )
    val service = akkaHttpService(config, wsLoggingEnabled = wsLoggingEnabled)
    val route = service(AkkaHttpServerConfig())
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), timeout)
    def exchange(): (Int, Either[String, Seq[String]]) = {
      val receive = Flow[Message]
        .mapAsync(1)(decode)
        .completionTimeout(5.seconds)
        .toMat(Sink.seq[String])(Keep.right)
      val client = Flow.fromSinkAndSourceMat(receive, Source.maybe[Message])(Keep.both)
      val (upgrade, (received, outgoing)) = Http().singleWebSocketRequest(
        WebSocketRequest(
          uri = s"ws://127.0.0.1:${binding.localAddress.getPort}/bridge/web-socket/missing-view",
          extraHeaders = List(Cookie("deviceId" -> "test-device"), RawHeader("Origin", "http://localhost")),
          subprotocol = Some("json")
        ),
        client
      )
      try {
        val status = Await.result(upgrade, timeout).response.status.intValue
        val outcome = Await.result(
          received
            .map[Either[String, Seq[String]]](frames => Right(frames))
            .recover { case error => Left(error.getClass.getSimpleName) },
          timeout
        )
        (status, outcome)
      } finally {
        outgoing.trySuccess(None)
      }
    }
    try {
      val (status, outcome) = exchange()
      status shouldBe StatusCodes.SwitchingProtocols.intValue
      rebuilds.get() shouldBe (if (control.isEmpty) 1 else 0)
      control.foreach { probe =>
        probe.opened.get() shouldBe 0
        probe.resumed.get() shouldBe 1
      }
      withClue(s"WebSocket upgrade=$status; received frames or terminal failure=$outcome: ") {
        outcome shouldBe Right(Seq("[1]"))
      }
      control.foreach { probe =>
        val (againStatus, again) = exchange()
        againStatus shouldBe StatusCodes.SwitchingProtocols.intValue
        again shouldBe Right(Seq("[1]"))
        probe.opened.get() shouldBe 0
        probe.resumed.get() shouldBe 2
      }
    } finally {
      Await.result(binding.terminate(2.seconds), timeout)
    }
  }

  "Missing local view over the official Akka adapter" - {
    "sends reload when an unguarded missing view cannot be rebuilt (control)" in {
      expectReload(None)
    }

    "sends reload when guarded recovery declines the missing view" in {
      expectReload(Some(new NoResumeControl))
    }

    "sends reload when frame logging is enabled" in {
      expectReload(Some(new NoResumeControl), wsLoggingEnabled = true)
    }
  }
}
