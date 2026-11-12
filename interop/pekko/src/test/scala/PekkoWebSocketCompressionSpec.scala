package spoonbill.pekko

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.server.{SpoonbillService, WebSocketRequest, WebSocketResponse}

/** Pekko HTTP 2.0 enables server-side `permessage-deflate` by default and caps
  * each inflated message at 256 KiB, independently of `wsMaxMessageBytes`.
  * Pekko HTTP's own client cannot negotiate compression, so these tests speak
  * the browser handshake over a raw socket.
  */
final class PekkoWebSocketCompressionSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  implicit private val system: ActorSystem        = ActorSystem("pekko-websocket-compression")
  implicit private val materializer: Materializer = Materializer(system)
  implicit private val ec: ExecutionContext       = system.dispatcher
  implicit private val effect: Effect[Future]     = Effect.futureEffect
  private val timeout                             = 5.seconds

  // Above Pekko HTTP's 256 KiB inflate cap, so a compressed path would drop it.
  private val messageBytes = 300 * 1024
  private val config       = PekkoHttpServerConfig(wsMaxMessageBytes = 512 * 1024)

  override def afterAll(): Unit =
    try Await.result(system.terminate(), timeout)
    finally super.afterAll()

  /** An output stream that stays open until it is canceled. */
  private def openOutput(): Stream[Future, Bytes] = {
    val pending = Promise[Option[Bytes]]()
    new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = pending.future
      def cancel(): Future[Unit]        = Future.successful { pending.trySuccess(None); () }
    }
  }

  /** Upgrade with the extension offer browsers send by default, then hand the
    * response headers and the open socket to `body`.
    */
  private def withCompressionOffer(server: SpoonbillService[Future])(body: (String, Socket) => Unit): Unit = {
    val route   = configureWsRoute(server, config, Reporter.PrintReporter, false)
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), timeout)
    val socket  = new Socket("127.0.0.1", binding.localAddress.getPort)
    try {
      socket.setSoTimeout(timeout.toMillis.toInt)
      val request =
        "GET /probe HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: json\r\nSec-WebSocket-Extensions: permessage-deflate; client_max_window_bits\r\n\r\n"
      socket.getOutputStream.write(request.getBytes(StandardCharsets.US_ASCII))
      socket.getOutputStream.flush()
      val headers = new StringBuilder
      while (!headers.endsWith("\r\n\r\n")) {
        val next = socket.getInputStream.read()
        next should not be -1
        headers.append(next.toChar)
      }
      body(headers.toString, socket)
    } finally {
      socket.close()
      Await.result(binding.terminate(1.second), timeout)
    }
  }

  "a browser that offers permessage-deflate" - {
    "is answered without compression on a duplex socket and keeps the adapter's message limit" in {
      val received = Promise[Option[Bytes]]()
      val output   = openOutput()
      val server = new SpoonbillService[Future] {
        def http(request: spoonbill.server.HttpRequest[Future]) = Future.failed(new IllegalStateException("unused"))
        def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = {
          request.httpRequest.body.pull().onComplete(received.tryComplete)
          Future.successful(WebSocketResponse.Duplex(output, "json", () => output.cancel()))
        }
      }
      withCompressionOffer(server) { (headers, socket) =>
        headers should include("101")
        headers.toLowerCase should not include "sec-websocket-extensions"

        // One masked, uncompressed binary frame (zero mask) of 300 KiB.
        val frame = new ByteArrayOutputStream
        val data  = new DataOutputStream(frame)
        data.writeByte(0x82)
        data.writeByte(0xff)
        data.writeLong(messageBytes.toLong)
        data.writeInt(0)
        data.write(Array.fill[Byte](messageBytes)(97))
        socket.getOutputStream.write(frame.toByteArray)
        socket.getOutputStream.flush()

        Await.result(received.future, timeout).map(_.length) shouldBe Some(messageBytes.toLong)
      }
    }

    "is answered without compression on a send-then-close socket" in {
      val output = openOutput()
      val server = new SpoonbillService[Future] {
        def http(request: spoonbill.server.HttpRequest[Future]) = Future.failed(new IllegalStateException("unused"))
        def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] =
          Future.successful(WebSocketResponse.SendThenClose(output, "json", () => output.cancel()))
      }
      withCompressionOffer(server) { (headers, _) =>
        headers should include("101")
        headers.toLowerCase should not include "sec-websocket-extensions"
      }
    }
  }
}
