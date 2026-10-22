package spoonbill.server

import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{RejectedExecutionException, TimeoutException}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}

final class StandaloneMessageDeadlineSpec extends AnyFlatSpec with Matchers {
  implicit private val ec: ExecutionContext = ExecutionContext.global
  implicit private val effect: Effect[Future] = Effect.futureEffect

  "Message completion" should "reject elapsed monotonic time before a delayed timer has executed" in {
    val time = new AtomicLong()
    val deadline = new MessageDeadline[Future](1.minute, () => Future.unit, () => time.get())
    try {
      deadline.start()
      time.set(1.minute.toNanos)
      intercept[TimeoutException](deadline.complete())
      time.set(0L)
      deadline.start()
      intercept[TimeoutException](deadline.complete())
    } finally deadline.close()
  }

  it should "close timed out input even when the application executor rejects work" in {
    given ExecutionContext = new ExecutionContext {
      def execute(task: Runnable): Unit = throw new RejectedExecutionException("Stopped application executor")
      def reportFailure(error: Throwable): Unit = ()
    }
    val expired = Promise[Unit]()
    val deadline = new MessageDeadline[Future](20.millis, () => {
      expired.trySuccess(())
      Future.unit
    })
    try {
      deadline.start()
      Await.result(expired.future, 2.seconds)
    } finally deadline.close()
  }

  private def withSocket(test: (Socket, Promise[Unit], AtomicInteger) => Unit, pauseAfterFirst: Boolean = false): Unit = {
    val released = Promise[Unit]()
    val releases = new AtomicInteger(0)
    val service = new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] = Future.failed(new IllegalStateException("unused"))
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = {
        val output = if (!pauseAfterFirst) request.httpRequest.body else new Stream[Future, Bytes] {
          private var first = true
          private val stopped = Promise[Option[Bytes]]()
          def pull(): Future[Option[Bytes]] = {
            if (first) { first = false; request.httpRequest.body.pull() }
            else stopped.future
          }
          def cancel(): Future[Unit] = { stopped.trySuccess(None); request.httpRequest.body.cancel() }
        }
        Future.successful(WebSocketResponse.Duplex(output, "json", () => Future.successful {
          releases.incrementAndGet(); released.trySuccess(()); ()
        }))
      }
    }
    val server = Await.result(standalone.buildServer[Future, Array[Byte]](
      service, new InetSocketAddress("127.0.0.1", 0), gracefulShutdown = false,
      wsMaxMessageBytes = 4, wsMessageTimeout = 200.millis
    ), 5.seconds)
    val socket = new Socket()
    try {
      socket.connect(server.localAddress, 3000)
      socket.setSoTimeout(2000)
      socket.getOutputStream.write((
        "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
        "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: json\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII))
      socket.getOutputStream.flush()
      val headers = new StringBuilder
      while (!headers.toString.endsWith("\r\n\r\n")) {
        val next = socket.getInputStream.read()
        if (next == -1) fail("Upgrade did not complete")
        headers.append(next.toChar)
      }
      headers.toString should startWith("HTTP/1.1 101")
      test(socket, released, releases)
    } finally {
      socket.close()
      Await.result(server.stopServingRequests(), 5.seconds)
    }
  }

  "Standalone message assembly" should "time out an unfinished frame without another incoming byte" in withSocket { (socket, released, count) =>
    socket.getOutputStream.write(Array[Byte](0x82.toByte, 0x84.toByte, 0, 0, 0, 0, 1))
    socket.getOutputStream.flush()
    socket.getInputStream.read() shouldBe -1
    Await.result(released.future, 2.seconds)
    count.get() shouldBe 1
  }

  it should "keep one deadline across fragments and interleaved control frames" in withSocket { (socket, released, count) =>
    socket.getOutputStream.write(Array[Byte](0x02, 0x81.toByte, 0, 0, 0, 0, 1))
    socket.getOutputStream.flush()
    Thread.sleep(100L)
    socket.getOutputStream.write(Array[Byte](0x89.toByte, 0x80.toByte, 0, 0, 0, 0))
    socket.getOutputStream.flush()
    socket.getInputStream.read() shouldBe -1
    Await.result(released.future, 2.seconds)
    count.get() shouldBe 1
  }

  it should "cancel the deadline on valid completion and accept later messages" in withSocket { (socket, _, _) =>
    def exchange(): Unit = {
      socket.getOutputStream.write(Array[Byte](0x82.toByte, 0x84.toByte, 0, 0, 0, 0, 1, 2, 3, 4))
      socket.getOutputStream.flush()
      socket.getInputStream.readNBytes(6).toSeq shouldBe Seq[Byte](0x82.toByte, 4, 1, 2, 3, 4)
    }
    exchange()
    Thread.sleep(300L)
    exchange()
  }

  it should "bound a decoder tail even while the application pauses after the preceding message" in withSocket(
    (socket, released, count) => {
      socket.getOutputStream.write(Array[Byte](
        0x82.toByte, 0x81.toByte, 0, 0, 0, 0, 1,
        0x82.toByte, 0x84.toByte, 0, 0, 0, 0, 2
      ))
      socket.getOutputStream.flush()
      socket.getInputStream.readNBytes(3).toSeq shouldBe Seq[Byte](0x82.toByte, 1, 1)
      socket.getInputStream.read() shouldBe -1
      Await.result(released.future, 2.seconds)
      count.get() shouldBe 1
    },
    pauseAfterFirst = true
  )
}
