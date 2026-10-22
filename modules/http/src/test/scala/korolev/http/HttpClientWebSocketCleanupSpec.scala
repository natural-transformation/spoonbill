package spoonbill.http

import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise, blocking}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.Reporter.PrintReporter.Implicit
import spoonbill.http.protocol.WebSocketProtocol.Frame
import spoonbill.web.{PathAndQuery, WebSocketLimits}

final class HttpClientWebSocketCleanupSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 8.seconds)

  private def upgrade(socket: Socket): Unit = {
    socket.setSoTimeout(5000)
    val headers = new StringBuilder
    while (!headers.toString.endsWith("\r\n\r\n") && headers.length < 8192) {
      val next = socket.getInputStream.read()
      if (next < 0) fail("Client closed before upgrade")
      headers.append(next.toChar)
    }
    val key = headers.toString.split("\r\n").find(_.startsWith("Sec-WebSocket-Key: ")).get
      .stripPrefix("Sec-WebSocket-Key: ")
    val digest = java.security.MessageDigest.getInstance("SHA-1")
      .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(US_ASCII))
    val accept = java.util.Base64.getEncoder.encodeToString(digest)
    socket.getOutputStream.write(("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n" +
      s"Upgrade: websocket\r\nSec-WebSocket-Accept: $accept\r\n\r\n").getBytes(US_ASCII))
    socket.getOutputStream.flush()
  }

  "HttpClient WebSocket rejection" should "close the physical socket, release output once and free its pool slot" in {
    val listener = new ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress)
    listener.setSoTimeout(5000)
    val firstClosed = Promise[Unit]()
    val server = Future(blocking {
      val first = listener.accept()
      try {
        upgrade(first)
        first.getOutputStream.write(Array[Byte](0x82.toByte, 5))
        first.getOutputStream.flush()
        first.getInputStream.read() shouldBe -1
        firstClosed.success(())
      } finally first.close()
      val second = listener.accept()
      try {
        upgrade(second)
        second.getOutputStream.write(Array[Byte](0x82.toByte, 4, 1, 2, 3, 4))
        second.getOutputStream.flush()
        second.getInputStream.read() shouldBe -1
      } finally second.close()
    })
    val failedReadSettled = Promise[Unit]()
    val outputReleased = Promise[Unit]()
    val cancellations = new AtomicInteger()
    val pending = Promise[Option[Frame[Bytes]]]()
    val output = new Stream[Future, Frame[Bytes]] {
      def pull(): Future[Option[Frame[Bytes]]] = pending.future
      def cancel(): Future[Unit] = {
        cancellations.incrementAndGet()
        pending.trySuccess(None)
        // Cleanup must not block the decoder failure that this finalizer awaits.
        failedReadSettled.future.map(_ => { outputReleased.trySuccess(()); () })
      }
    }
    try {
      val client = await(HttpClient.create[Future, Bytes](maxConnectionsPerAddress = 1, maxWebSocketMessageBytes = 4))
      val address = new InetSocketAddress(java.net.InetAddress.getLoopbackAddress, listener.getLocalPort)
      val first = await(client.webSocket(address, PathAndQuery.Root, output, Map.empty, Map.empty))
      intercept[WebSocketLimits.Exceeded](await(first.body.pull()))
      failedReadSettled.success(())
      await(firstClosed.future)
      await(outputReleased.future)
      await(first.body.cancel())
      cancellations.get() shouldBe 1
      val second = await(client.webSocket(address, PathAndQuery.Root, Stream.empty[Future, Frame[Bytes]], Map.empty, Map.empty))
      await(second.body.pull()) shouldBe Some(Frame.Binary(Bytes(1, 2, 3, 4)))
      await(second.body.cancel())
      await(server)
    } finally {
      failedReadSettled.trySuccess(())
      listener.close()
    }
  }
}
