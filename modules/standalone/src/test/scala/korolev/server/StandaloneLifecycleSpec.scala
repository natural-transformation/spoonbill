package spoonbill.server

import java.net.{InetSocketAddress, Socket, URI}
import java.net.http.{HttpClient, WebSocket}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets
import java.util.concurrent.{Executors, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.syntax.*

/** Finalization for terminal output: peer loss and a failed send. */
final class StandaloneLifecycleSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val timeout = 8.seconds

  private def service(response: WebSocketResponse[Future]): SpoonbillService[Future] =
    new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] =
        Future.failed(new IllegalStateException("unused"))
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] =
        Future.successful(response)
    }

  private final class SocketProbe(
    captureInput: Boolean,
    releaseCancelsInput: Boolean = false,
    releaseWaitsForInput: Boolean = false
  ) {
    val next = Promise[Option[Bytes]]()
    val pulled = Promise[Unit]()
    val released = Promise[Unit]()
    val requestInput = Promise[Stream[Future, Bytes]]()
    val output = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = effect.delay { pulled.trySuccess(()); () } *> next.future
      def cancel(): Future[Unit] = effect.delay { next.trySuccess(None); () }
    }
    val service = new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] =
        Future.failed(new IllegalStateException("unused"))
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = {
        if (captureInput) requestInput.trySuccess(request.httpRequest.body)
        val inputFinished =
          if (releaseWaitsForInput) request.httpRequest.body.pull().map(_ => ()).recover { case _ => () }
          else Future.unit
        val release = WebSocketResponse.releaseOnce[Future](() =>
          inputFinished *> (if (releaseCancelsInput) request.httpRequest.body.cancel() else Future.unit) *>
            output.cancel() *> effect.delay { released.trySuccess(()); () }
        )
        val response =
          if (captureInput) WebSocketResponse.Duplex(output, "json", release)
          else WebSocketResponse.SendThenClose(output, "json", release)
        Future.successful(response)
      }
    }
  }

  private def withUpgradedSocket(probe: SocketProbe)(f: (SocketProbe, Socket) => Unit): Unit = {
    val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    val server = Await.result(
      standalone.buildServer[Future, Array[Byte]](
        probe.service,
        new InetSocketAddress("127.0.0.1", 0),
        group,
        gracefulShutdown = false
      ),
      timeout
    )
    val socket = new Socket()
    try {
      socket.connect(server.localAddress, 3000)
      socket.setSoTimeout(3000)
      socket.getOutputStream.write(
        (
          "GET /socket HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: Upgrade\r\n" +
            "Upgrade: websocket\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Protocol: json\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII)
      )
      socket.getOutputStream.flush()
      val header = new StringBuilder
      while (!header.toString.endsWith("\r\n\r\n") && header.length < 8192) {
        val byte = socket.getInputStream.read()
        if (byte == -1) fail("Socket closed before upgrade completed")
        header.append(byte.toChar)
      }
      header.toString should startWith("HTTP/1.1 101")
      Await.result(probe.pulled.future, timeout)
      f(probe, socket)
    } finally {
      socket.close()
      probe.next.trySuccess(None)
      Await.result(server.stopServingRequests(), timeout)
      group.shutdownNow()
      group.awaitTermination(3, TimeUnit.SECONDS)
    }
  }

  private def withDuplex(f: (SocketProbe, Socket) => Unit): Unit =
    withUpgradedSocket(new SocketProbe(captureInput = true))(f)

  private def withRawTerminal(f: (SocketProbe, Socket) => Unit): Unit =
    withUpgradedSocket(new SocketProbe(captureInput = false))(f)

  private def pendingOutput(): (Promise[Unit], Stream[Future, Bytes]) = {
    val requested = Promise[Unit]()
    val gate = Promise[Option[Bytes]]()
    val output = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = effect.delay { requested.trySuccess(()); () } *> gate.future
      def cancel(): Future[Unit] = effect.delay { gate.trySuccess(None); () }
    }
    (requested, output)
  }

  "SendThenClose over standalone" should "release the response when the peer leaves during a pending pull" in {
    val released = Promise[Unit]()
    val release = WebSocketResponse.releaseOnce[Future](() => Future.successful { released.trySuccess(()); () })
    val (requested, output) = pendingOutput()
    val response = WebSocketResponse.SendThenClose(output, "json", release)
    val handler = Await.result(
      standalone.buildServer[Future, Array[Byte]](service(response), new InetSocketAddress("127.0.0.1", 0), null, false),
      timeout
    )
    val port = handler.localAddress.asInstanceOf[InetSocketAddress].getPort
    val client = HttpClient.newHttpClient()
    try {
      val socket = client
        .newWebSocketBuilder()
        .subprotocols("json")
        .buildAsync(URI.create(s"ws://127.0.0.1:$port/bridge/web-socket/pending"), new WebSocket.Listener {})
        .join()
      Await.result(requested.future, timeout)
      socket.abort()
      Await.result(released.future, timeout)
    } finally {
      Await.result(handler.stopServingRequests(), timeout)
      client.close()
    }
  }

  it should "release the response when an output pull fails" in {
    val released = Promise[Unit]()
    val release = WebSocketResponse.releaseOnce[Future](() => Future.successful { released.trySuccess(()); () })
    val output = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = Future.failed(new IllegalStateException("output failed"))
      def cancel(): Future[Unit] = Future.unit
    }
    val response = WebSocketResponse.SendThenClose(output, "json", release)
    val handler = Await.result(
      standalone.buildServer[Future, Array[Byte]](service(response), new InetSocketAddress("127.0.0.1", 0), null, false),
      timeout
    )
    val port = handler.localAddress.asInstanceOf[InetSocketAddress].getPort
    val client = HttpClient.newHttpClient()
    try {
      try {
        client
          .newWebSocketBuilder()
          .subprotocols("json")
          .buildAsync(URI.create(s"ws://127.0.0.1:$port/bridge/web-socket/fail"), new WebSocket.Listener {})
          .join()
      } catch { case _: Throwable => () }
      Await.result(released.future, timeout)
    } finally {
      Await.result(handler.stopServingRequests(), timeout)
      client.close()
    }
  }

  "Duplex over standalone" should "cancel pending output and release after application input cancellation" in {
    withDuplex { (probe, socket) =>
      val input = Await.result(probe.requestInput.future, timeout)
      val pendingRead = input.pull()
      Await.result(input.cancel(), timeout)
      Await.result(probe.released.future, timeout)
      probe.next.isCompleted shouldBe true
      Await.ready(pendingRead, timeout)
      socket.getInputStream.read() shouldBe -1
    }
  }

  it should "abort pending output and release after application input observes peer EOF" in {
    withDuplex { (probe, socket) =>
      val input = Await.result(probe.requestInput.future, timeout)
      val pendingRead = input.pull()
      socket.close()
      Await.result(pendingRead, timeout) shouldBe None
      Await.result(probe.released.future, timeout)
      probe.next.isCompleted shouldBe true
    }
  }

  it should "finish when response release cancels its own input" in {
    withUpgradedSocket(new SocketProbe(captureInput = true, releaseCancelsInput = true)) { (probe, socket) =>
      probe.next.success(None)
      // Empty Close frame followed by physical transport closure.
      socket.getInputStream.read() shouldBe 0x88
      socket.getInputStream.read() shouldBe 0
      socket.getInputStream.read() shouldBe -1
      Await.result(probe.released.future, timeout)
    }
  }

  it should "reply to a peer Close while output is pending and release cancels input" in {
    withUpgradedSocket(new SocketProbe(captureInput = true, releaseCancelsInput = true)) { (probe, socket) =>
      val input = Await.result(probe.requestInput.future, timeout)
      val pendingRead = input.pull()
      socket.getOutputStream.write(Array[Byte](0x88.toByte, 0x80.toByte, 0, 0, 0, 0))
      socket.getOutputStream.flush()
      Await.result(pendingRead, timeout) shouldBe None
      socket.getInputStream.read() shouldBe 0x88
      socket.getInputStream.read() shouldBe 0
      socket.getInputStream.read() shouldBe -1
      Await.result(probe.released.future, timeout)
    }
  }

  it should "close the transport before a normal finalizer waits for its outstanding input read" in {
    withUpgradedSocket(new SocketProbe(captureInput = true, releaseWaitsForInput = true)) { (probe, socket) =>
      probe.next.success(None)
      // The peer keeps TCP open and sends nothing. Closing must unblock the
      // read that the service started during setup before awaiting its release.
      socket.getInputStream.read() shouldBe 0x88
      socket.getInputStream.read() shouldBe 0
      socket.getInputStream.read() shouldBe -1
      Await.result(probe.released.future, timeout)
    }
  }

  it should "return from inbound Close conversion before a finalizer awaits that read" in {
    withUpgradedSocket(new SocketProbe(captureInput = true, releaseWaitsForInput = true)) { (probe, socket) =>
      socket.getOutputStream.write(Array[Byte](0x88.toByte, 0x80.toByte, 0, 0, 0, 0))
      socket.getOutputStream.flush()
      socket.getInputStream.read() shouldBe 0x88
      socket.getInputStream.read() shouldBe 0
      socket.getInputStream.read() shouldBe -1
      Await.result(probe.released.future, timeout)
    }
  }

  "SendThenClose over standalone" should "release after a peer WebSocket close frame while output is pending" in {
    withRawTerminal { (probe, socket) =>
      // Masked empty Close. TCP stays open; the client waits for the server close.
      socket.getOutputStream.write(Array[Byte](0x88.toByte, 0x80.toByte, 0, 0, 0, 0))
      socket.getOutputStream.flush()
      Await.result(probe.released.future, timeout)
      probe.next.isCompleted shouldBe true
    }
  }

  "Coupled standalone input" should "read the transport directly after handoff" in {
    val upstream = Await.result(Stream.emits(Seq(1, 2)).mat[Future](), timeout)
    val handoff = new SetupInput(upstream)
    Await.result(handoff.couple(), timeout) shouldBe false
    Await.result(handoff.stream.pull(), timeout) shouldBe Some(1)
    Await.result(handoff.stream.pull(), timeout) shouldBe Some(2)
    Await.result(handoff.stream.pull(), timeout) shouldBe None
  }

  "Failed standalone setup" should "settle an application read without canceling the HTTP rejection" in {
    val inputRead = Promise[Future[Option[Bytes]]]()
    val failing = new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] =
        Future.failed(new IllegalStateException("unused"))
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = {
        inputRead.success(request.httpRequest.body.pull())
        Future.failed(new IllegalStateException("intentional setup failure"))
      }
    }
    val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    val server = Await.result(
      standalone.buildServer[Future, Array[Byte]](failing, new InetSocketAddress("127.0.0.1", 0), group, false),
      timeout
    )
    val port = server.localAddress.asInstanceOf[InetSocketAddress].getPort
    val client = HttpClient.newHttpClient()
    try {
      val error = intercept[java.util.concurrent.CompletionException] {
        client.newWebSocketBuilder().subprotocols("json")
          .buildAsync(URI.create(s"ws://127.0.0.1:$port/fail"), new WebSocket.Listener {}).join()
      }
      error.getCause.asInstanceOf[java.net.http.WebSocketHandshakeException].getResponse.statusCode() shouldBe 500
      Await.result(Await.result(inputRead.future, timeout), timeout) shouldBe None
    } finally {
      Await.result(server.stopServingRequests(), timeout)
      group.shutdownNow()
      group.awaitTermination(3, TimeUnit.SECONDS)
      client.close()
    }
  }
}
