package spoonbill.server

import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.web.WebSocketLimits

final class StandaloneWebSocketLimitsSpec extends AnyFlatSpec with Matchers {
  implicit private val ec: ExecutionContext   = ExecutionContext.global
  implicit private val effect: Effect[Future] = Effect.futureEffect
  private val timeout                         = 5.seconds

  "Standalone WebSocket ingress" should "apply configured limits and release idle output on decoder rejection" in {
    val released      = Promise[Unit]()
    val failedInput   = Promise[Throwable]()
    val releases      = new AtomicInteger(0)
    val pendingOutput = Promise[Option[Bytes]]()
    val output = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = pendingOutput.future
      def cancel(): Future[Unit]        = Future.successful { pendingOutput.trySuccess(None); () }
    }
    val service = new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] =
        Future.failed(new IllegalStateException("unused"))
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = {
        val read = request.httpRequest.body.pull()
        read.failed.foreach(failedInput.trySuccess)
        Future.successful(
          WebSocketResponse.Duplex(
            output,
            "json",
            () =>
              // Regression for a finalizer awaiting the failed read: teardown must
              // not wait inside the read's failure continuation.
              read.recover { case _ => None }.map { _ =>
                releases.incrementAndGet(); released.trySuccess(()); ()
              }
          )
        )
      }
    }
    val server = Await.result(
      standalone.buildServer[Future, Array[Byte]](
        service,
        new InetSocketAddress("127.0.0.1", 0),
        gracefulShutdown = false,
        wsMaxMessageBytes = 4
      ),
      timeout
    )
    val socket = new Socket()
    try {
      socket.connect(server.localAddress, 3000)
      socket.setSoTimeout(3000)
      socket.getOutputStream.write(
        (
          "GET /socket HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
            "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: json\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII)
      )
      socket.getOutputStream.flush()
      val header = new StringBuilder
      while (!header.toString.endsWith("\r\n\r\n")) {
        val next = socket.getInputStream.read()
        if (next == -1) fail("Socket closed before upgrade")
        header.append(next.toChar)
      }
      header.toString should startWith("HTTP/1.1 101")
      // Reject immediately from the header; neither mask nor payload arrives.
      socket.getOutputStream.write(Array[Byte](0x82.toByte, 0x85.toByte))
      socket.getOutputStream.flush()
      Await.result(failedInput.future, timeout) shouldBe a[WebSocketLimits.Exceeded]
      Await.result(released.future, timeout)
      socket.getInputStream.read() shouldBe -1
      releases.get() shouldBe 1
    } finally {
      socket.close()
      Await.result(server.stopServingRequests(), timeout)
    }
  }
}
