package spoonbill.server

import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Effect

final class StandaloneDeadlineIsolationSpec extends AnyFlatSpec with Matchers {
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private val upgrade = ("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
    "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: json\r\n\r\n").getBytes(US_ASCII)

  "Standalone deadlines" should "close another socket while cleanup is blocked and reject excess admission before the handler" in {
    val entered = new CountDownLatch(1)
    val releaseGate = new CountDownLatch(1)
    val asyncCleanup = Promise[Unit]()
    val releases = new AtomicInteger()
    val handlers = new AtomicInteger()
    val bothReleased = Promise[Unit]()
    val service = new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] = Future.failed(new IllegalStateException("unused"))
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = {
        handlers.incrementAndGet()
        Future.successful(WebSocketResponse.Duplex(request.httpRequest.body, "json", () => {
          val count = releases.incrementAndGet()
          if (count == 1) {
            entered.countDown()
            releaseGate.await(10L, TimeUnit.SECONDS)
            asyncCleanup.future
          } else { bothReleased.trySuccess(()); Future.unit }
        }))
      }
    }
    val server = Await.result(standalone.buildServer[Future, Array[Byte]](
      service, new InetSocketAddress("127.0.0.1", 0), gracefulShutdown = true,
      wsMessageTimeout = 100.millis, maxConnections = 2, closeNotificationWorkers = 1
    ), 5.seconds)
    var sockets = List.empty[Socket]
    def connect(upgradeConnection: Boolean): Socket = {
      val socket = new Socket()
      sockets ::= socket
      socket.connect(server.localAddress, 3000)
      socket.setSoTimeout(1500)
      if (upgradeConnection) {
        socket.getOutputStream.write(upgrade)
        socket.getOutputStream.flush()
        val headers = new StringBuilder
        while (!headers.toString.endsWith("\r\n\r\n")) {
          val next = socket.getInputStream.read()
          assert(next != -1, "Upgrade did not complete")
          headers.append(next.toChar)
        }
        headers.toString should startWith("HTTP/1.1 101")
      }
      socket
    }
    def unfinished(socket: Socket): Unit = {
      socket.getOutputStream.write(Array[Byte](0x82.toByte))
      socket.getOutputStream.flush()
    }
    try {
      val first = connect(true)
      unfinished(first)
      entered.await(2L, TimeUnit.SECONDS) shouldBe true
      first.getInputStream.read() shouldBe -1
      val second = connect(true)
      unfinished(second)
      second.getInputStream.read() shouldBe -1
      // Both sockets are physically closed, but their cleanup reservations are
      // still occupied. Admission must not invoke a third application handler.
      val excess = connect(false)
      excess.getInputStream.read() shouldBe -1
      handlers.get() shouldBe 2
      releaseGate.countDown()
      Await.result(bothReleased.future, 3.seconds)
      Await.result(server.stopServingRequests(), 3.seconds)
      val stopped = server.awaitShutdown()
      stopped.isCompleted shouldBe false
      asyncCleanup.success(())
      // An aborted NIO read may be retained as the server's terminal failure;
      // either outcome must wait for cleanup before settling.
      Await.ready(stopped, 3.seconds)
      releases.get() shouldBe 2
    } finally {
      releaseGate.countDown()
      asyncCleanup.trySuccess(())
      sockets.foreach(_.close())
      Await.result(server.stopServingRequests(), 5.seconds)
    }
  }
}
