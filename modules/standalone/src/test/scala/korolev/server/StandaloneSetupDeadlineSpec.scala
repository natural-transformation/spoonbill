package spoonbill.server

import java.net.{InetSocketAddress, Socket}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets
import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}

final class StandaloneSetupDeadlineSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val timeout = 5.seconds

  private final class OwnershipProbe {
    val inputs = new AtomicInteger()
    val sockets = new AtomicInteger()
    val responses = new AtomicInteger()
    val deadline = new SetupDeadline[Future](
      1.hour,
      () => effect.delay { inputs.incrementAndGet(); () },
      () => effect.delay { sockets.incrementAndGet(); () }
    )
    val release: () => Future[Unit] = () => effect.delay { responses.incrementAndGet(); () }
  }

  "Standalone setup ownership" should "dispose a late response after expiry exactly once" in {
    val probe = new OwnershipProbe
    try {
      Await.result(probe.deadline.expire(), timeout)
      Await.result(probe.deadline.acquire(probe.release), timeout) shouldBe false
      Await.result(probe.deadline.attach(), timeout) shouldBe false
      Await.result(probe.deadline.expire(), timeout)
      probe.inputs.get() shouldBe 1
      probe.sockets.get() shouldBe 1
      probe.responses.get() shouldBe 1
    } finally Await.result(probe.deadline.transportEnded(), timeout)
  }

  it should "dispose an acquired but unattached response when setup expires" in {
    val probe = new OwnershipProbe
    try {
      Await.result(probe.deadline.acquire(probe.release), timeout) shouldBe true
      Await.result(probe.deadline.expire(), timeout)
      Await.result(probe.deadline.attach(), timeout) shouldBe false
      probe.responses.get() shouldBe 1
    } finally Await.result(probe.deadline.transportEnded(), timeout)
  }

  it should "leave an attached connection active when its old deadline fires" in {
    val probe = new OwnershipProbe
    try {
      Await.result(probe.deadline.acquire(probe.release), timeout) shouldBe true
      Await.result(probe.deadline.attach(), timeout) shouldBe true
      Await.result(probe.deadline.expire(), timeout)
      probe.inputs.get() shouldBe 0
      probe.sockets.get() shouldBe 0
      probe.responses.get() shouldBe 0
      Await.result(probe.deadline.transportEnded(), timeout)
      probe.responses.get() shouldBe 1
    } finally Await.result(probe.deadline.transportEnded(), timeout)
  }

  it should "detach a rejected setup observer and ignore a close callback already in flight" in {
    val probe = new OwnershipProbe
    val detached = new AtomicInteger()
    probe.deadline.onFinished(() => { detached.incrementAndGet(); () })
    try {
      Await.result(probe.deadline.reject(), timeout)
      detached.get() shouldBe 1
      Await.result(probe.deadline.transportEnded(), timeout)
      probe.inputs.get() shouldBe 1
      probe.sockets.get() shouldBe 0
    } finally Await.result(probe.deadline.transportEnded(), timeout)
  }

  it should "detach an observer registered after ownership already ended" in {
    val probe = new OwnershipProbe
    val detached = new AtomicInteger()
    try {
      Await.result(probe.deadline.reject(), timeout)
      probe.deadline.onFinished(() => { detached.incrementAndGet(); () })
      detached.get() shouldBe 1
    } finally Await.result(probe.deadline.transportEnded(), timeout)
  }

  private def withPendingSetup(f: (Socket, Promise[WebSocketResponse[Future]], Future[Option[Bytes]]) => Unit): Unit = {
    val result = Promise[WebSocketResponse[Future]]()
    val reading = Promise[Future[Option[Bytes]]]()
    val service = new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] =
        Future.failed(new IllegalStateException("unused"))
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = {
        reading.success(request.httpRequest.body.pull())
        result.future
      }
    }
    val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    val server = Await.result(
      standalone.buildServer[Future, Array[Byte]](
        service, new InetSocketAddress("127.0.0.1", 0), group, false, wsSetupTimeout = 100.millis
      ), timeout
    )
    val socket = new Socket()
    try {
      socket.connect(server.localAddress, 3000)
      socket.setSoTimeout(3000)
      socket.getOutputStream.write((
        "GET /socket HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
          "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
          "Sec-WebSocket-Protocol: json\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII))
      f(socket, result, Await.result(reading.future, timeout))
    } finally {
      socket.close()
      result.trySuccess(WebSocketResponse.SendThenClose(Stream.empty[Future, Bytes], "json", () => Future.unit))
      Await.result(server.stopServingRequests(), timeout)
      group.shutdownNow()
      group.awaitTermination(3, TimeUnit.SECONDS)
    }
  }

  "A pending standalone upgrade" should "release input after peer disconnect even if the service never completes" in {
    withPendingSetup { (socket, _, read) =>
      socket.close()
      Await.result(read, timeout) shouldBe None
    }
  }

  it should "close the socket on expiry and dispose a late response without pulling output" in {
    withPendingSetup { (socket, result, read) =>
      socket.getInputStream.read() shouldBe -1
      Await.result(read, timeout) shouldBe None
      val pulls = new AtomicInteger()
      val released = Promise[Unit]()
      val output = new Stream[Future, Bytes] {
        def pull(): Future[Option[Bytes]] = effect.delay { pulls.incrementAndGet(); None }
        def cancel(): Future[Unit] = Future.unit
      }
      result.success(WebSocketResponse.Duplex(output, "json", () => effect.delay { released.trySuccess(()); () }))
      Await.result(released.future, timeout)
      pulls.get() shouldBe 0
    }
  }

  "Standalone setup configuration" should "reject a non-positive deadline before binding" in {
    intercept[IllegalArgumentException] {
      standalone.buildServer[Future, Array[Byte]](null, new InetSocketAddress("127.0.0.1", 0), null, false, Duration.Zero)
    }
  }
}
