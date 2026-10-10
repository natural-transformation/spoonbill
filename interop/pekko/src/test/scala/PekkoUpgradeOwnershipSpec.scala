package spoonbill.pekko

import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.server.{SpoonbillService, WebSocketRequest as SpoonbillWebSocketRequest, WebSocketResponse}

final class PekkoUpgradeOwnershipSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("pekko-upgrade-ownership-spec")
  private implicit val materializer: Materializer = Materializer(system)
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private val timeout = 8.seconds

  override def afterAll(): Unit =
    try Await.result(system.terminate(), timeout)
    finally super.afterAll()

  private final class ProbeInput extends Stream[Future, Bytes] {
    val pending = Promise[Option[Bytes]]()
    val cancellations = new AtomicInteger(0)
    def pull(): Future[Option[Bytes]] = pending.future
    def cancel(): Future[Unit] = effect.delay {
      cancellations.incrementAndGet()
      pending.trySuccess(None)
      ()
    }
  }

  private def response(released: Promise[Unit], pulls: AtomicInteger): WebSocketResponse[Future] = {
    val output = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = effect.delay { pulls.incrementAndGet(); None }
      def cancel(): Future[Unit] = Future.unit
    }
    WebSocketResponse.Duplex(output, "json", () => effect.delay { released.trySuccess(()); () })
  }

  "Upgrade ownership" - {
    "lets abort win before attachment and disposes a prepared response" in {
      val input = new ProbeInput
      val released = Promise[Unit]()
      val owner = new UpgradeOwnership[Future](HttpRequest(), input, 30.seconds)
      Await.result(owner.finish(response(released, new AtomicInteger(0)))(Future.successful(HttpResponse())), timeout)
      Await.result(owner.abort(), timeout)
      intercept[TimeoutException](owner.attached())
      Await.result(released.future, timeout)
      Await.result(input.pending.future, timeout) shouldBe None
      Await.result(owner.abort(), timeout)
      input.cancellations.get() shouldBe 1
    }

    "lets attachment win without later setup abort releasing an active session" in {
      val input = new ProbeInput
      val released = Promise[Unit]()
      val owner = new UpgradeOwnership[Future](HttpRequest(), input, 30.seconds)
      Await.result(owner.finish(response(released, new AtomicInteger(0)))(Future.successful(HttpResponse())), timeout)
      owner.attached()
      Await.result(owner.abort(), timeout)
      input.cancellations.get() shouldBe 0
      released.isCompleted shouldBe false
      Await.result(input.cancel(), timeout)
    }

    "releases both owners if constructing the upgrade throws" in {
      val input = new ProbeInput
      val released = Promise[Unit]()
      val owner = new UpgradeOwnership[Future](HttpRequest(), input, 30.seconds)
      intercept[IllegalArgumentException] {
        Await.result(owner.finish(response(released, new AtomicInteger(0))) {
          throw new IllegalArgumentException("construction failed")
        }, timeout)
      }
      Await.result(released.future, timeout)
      input.cancellations.get() shouldBe 1
    }

    "preserves the caller's HTTP timeout response and releases late output" in {
      val entered = Promise[Unit]()
      val gate = Promise[WebSocketResponse[Future]]()
      val released = Promise[Unit]()
      val pulls = new AtomicInteger(0)
      val server = new SpoonbillService[Future] {
        def http(request: spoonbill.server.HttpRequest[Future]) = Future.failed(new IllegalStateException("unused"))
        def ws(request: SpoonbillWebSocketRequest[Future]) = { entered.trySuccess(()); gate.future }
      }
      val route = withRequestTimeout(300.millis, _ => HttpResponse(StatusCodes.GatewayTimeout, entity = "custom deadline")) {
        configureWsRoute(server, PekkoHttpServerConfig(), Reporter.PrintReporter, false)
      }
      val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), timeout)
      val socket = new Socket("127.0.0.1", binding.localAddress.getPort)
      try {
        // The WebSocket client strips a rejected upgrade's entity. Read the
        // actual HTTP response so both the custom status and body are checked.
        socket.setSoTimeout(timeout.toMillis.toInt)
        val request = "GET /probe HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: json\r\n\r\n"
        socket.getOutputStream.write(request.getBytes(StandardCharsets.US_ASCII))
        socket.getOutputStream.flush()
        Await.result(entered.future, timeout)
        val rejected = new String(socket.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        rejected should startWith("HTTP/1.1 504 ")
        rejected.split("\r\n\r\n", 2).last shouldBe "custom deadline"
        gate.success(response(released, pulls))
        Await.result(released.future, timeout)
        pulls.get() shouldBe 0
      } finally {
        socket.close()
        Await.result(binding.terminate(2.seconds), timeout)
      }
    }

    "bounds setup cleanup after peer disconnect even with HTTP timeouts disabled" in {
      val input = Promise[Stream[Future, Bytes]]()
      val gate = Promise[WebSocketResponse[Future]]()
      val released = Promise[Unit]()
      val pulls = new AtomicInteger(0)
      val server = new SpoonbillService[Future] {
        def http(request: spoonbill.server.HttpRequest[Future]) = Future.failed(new IllegalStateException("unused"))
        def ws(request: SpoonbillWebSocketRequest[Future]) = { input.trySuccess(request.httpRequest.body); gate.future }
      }
      val route = withoutRequestTimeout {
        configureWsRoute(server, PekkoHttpServerConfig(wsSetupTimeout = 300.millis), Reporter.PrintReporter, false)
      }
      val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), timeout)
      val socket = new Socket("127.0.0.1", binding.localAddress.getPort)
      try {
        val request = "GET /probe HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: json\r\n\r\n"
        socket.getOutputStream.write(request.getBytes(StandardCharsets.US_ASCII))
        socket.getOutputStream.flush()
        val pending = Await.result(input.future, timeout).pull()
        socket.close()
        Await.result(pending, timeout) shouldBe None
        gate.success(response(released, pulls))
        Await.result(released.future, timeout)
        pulls.get() shouldBe 0
      } finally {
        socket.close()
        Await.result(binding.terminate(2.seconds), timeout)
      }
    }
  }
}
