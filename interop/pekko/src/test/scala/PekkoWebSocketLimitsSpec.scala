package spoonbill.pekko

import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ws.{BinaryMessage, TextMessage}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.server.{SpoonbillService, WebSocketRequest, WebSocketResponse}
import spoonbill.web.WebSocketLimits

final class PekkoWebSocketLimitsSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  implicit private val system: ActorSystem        = ActorSystem("pekko-websocket-limits")
  implicit private val materializer: Materializer = Materializer(system)
  implicit private val ec: ExecutionContext       = system.dispatcher
  implicit private val effect: Effect[Future]     = Effect.futureEffect
  private val timeout                             = 5.seconds
  private val config                              = PekkoHttpServerConfig(wsMaxMessageBytes = 4, wsStreamedCompletionTimeout = 150.millis)

  override def afterAll(): Unit =
    try Await.result(system.terminate(), timeout)
    finally super.afterAll()

  "message admission" - {
    "counts UTF-8 bytes and preserves surrogate pairs across chunks" in {
      val message = TextMessage(Source(List("\uD83D", "", "\uDE00")))
      Await.result(boundedMessage(message, config), timeout).asUtf8String shouldBe "😀"
      intercept[WebSocketLimits.Exceeded] {
        Await.result(boundedMessage(TextMessage("ééa"), config), timeout)
      }
      Await.result(boundedMessage(TextMessage("éé"), config), timeout).asUtf8String shouldBe "éé"
    }
    "rejects streamed bytes before waiting for the rest of an oversized message" in {
      val message = BinaryMessage(Source(List(ByteString("1234"), ByteString("5"))).concat(Source.maybe[ByteString]))
      intercept[WebSocketLimits.Exceeded](Await.result(boundedMessage(message, config), timeout))
      Await.result(boundedMessage(BinaryMessage(ByteString("1234")), config), timeout).length shouldBe 4L
    }
    "times out a small but unfinished message and rejects incomplete UTF-16" in {
      intercept[java.util.concurrent.TimeoutException] {
        Await.result(boundedMessage(TextMessage(Source.single("a").concat(Source.maybe[String])), config), timeout)
      }
      intercept[IllegalArgumentException] {
        Await.result(boundedMessage(TextMessage(Source.single("\uD83D")), config), timeout)
      }
    }
    "stops a huge unfragmented wire frame without waiting for its advertised payload" in {
      val released = Promise[Unit]()
      val releases = new AtomicInteger(0)
      val pending  = Promise[Option[Bytes]]()
      val output = new Stream[Future, Bytes] {
        def pull(): Future[Option[Bytes]] = pending.future
        def cancel(): Future[Unit]        = Future.successful { pending.trySuccess(None); () }
      }
      val server = new SpoonbillService[Future] {
        def http(request: spoonbill.server.HttpRequest[Future]) = Future.failed(new IllegalStateException("unused"))
        def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = {
          // Demand is essential: the transport must not pull while the app is paused.
          request.httpRequest.body.pull()
          Future.successful(
            WebSocketResponse.Duplex(
              output,
              "json",
              () =>
                Future.successful {
                  releases.incrementAndGet(); released.trySuccess(()); ()
                }
            )
          )
        }
      }
      val route   = configureWsRoute(server, config, Reporter.PrintReporter, false)
      val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), timeout)
      val socket  = new Socket("127.0.0.1", binding.localAddress.getPort)
      try {
        socket.setSoTimeout(timeout.toMillis.toInt)
        val request =
          "GET /probe HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: json\r\n\r\n"
        socket.getOutputStream.write(request.getBytes(StandardCharsets.US_ASCII))
        socket.getOutputStream.flush()
        val headers = new StringBuilder
        while (!headers.endsWith("\r\n\r\n")) {
          val next = socket.getInputStream.read()
          next should not be -1
          headers.append(next.toChar)
        }
        headers.toString should include("101")
        // Advertise 65535 bytes but send only five, with a zero mask.
        socket.getOutputStream.write(
          Array[Byte](0x82.toByte, 0xfe.toByte, 0xff.toByte, 0xff.toByte, 0, 0, 0, 0, 1, 2, 3, 4, 5)
        )
        socket.getOutputStream.flush()
        Await.result(released.future, timeout)
        releases.get() shouldBe 1
      } finally {
        socket.close()
        Await.result(binding.terminate(1.second), timeout)
      }
    }
  }
}
