package spoonbill.server

import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, Socket, SocketException}
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/** Real authentication body consumption over chunked standalone HTTP. */
final class StandaloneChunkedAuthenticationSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private val binding = "B" * 43
  private val token = "T" * 43

  private final class Fixture(bodyTimeout: FiniteDuration) {
    val delivered = new AtomicInteger()
    val deliveredHandle = new AtomicReference[UUID]()
    val bodyBytes = new AtomicInteger()
    val canceled = new AtomicInteger()
    private val sockets = scala.collection.mutable.ArrayBuffer.empty[Socket]
    private val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
      def open(qsid: Qsid, request: Request.Head, id: ConnectionId): Future[SessionGuard[Future, String]] =
        Future.failed(new IllegalStateException("Unused WebSocket"))
    }
    private val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("public"),
      sessionAccessControl = Some(control),
      authenticationCompletion = Some(AuthenticationCompletionConfig[Future](
        "binding", "session", Set("http://localhost"), false, 60,
        (handle, supplied) => Future.successful {
          delivered.incrementAndGet()
          deliveredHandle.set(handle)
          if (supplied == binding) BrowserSessionToken.fromString(token).toOption else None
        },
        (_, _) => Future.unit
      )),
      http = { case request if request.pq == PathAndQuery.fromString("/health") => HttpResponse.ok("healthy") }
    )
    private val application = spoonbillService(config)
    private val observed = new SpoonbillService[Future] {
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = application.ws(request)
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] =
        if (request.pq != PathAndQuery.fromString("/auth/complete")) application.http(request)
        else application.http(request.copy(body = new Stream[Future, Bytes] {
          def pull(): Future[Option[Bytes]] = request.body.pull().map { next =>
            next.foreach(bytes => bodyBytes.addAndGet(bytes.length.toInt))
            next
          }
          def cancel(): Future[Unit] = { canceled.incrementAndGet(); request.body.cancel() }
        }))
    }
    private val server = await(standalone.buildServer[Future, Array[Byte]](
      observed, new InetSocketAddress("127.0.0.1", 0), gracefulShutdown = false,
      requestHeaderTimeout = 10.seconds, requestBodyTimeout = bodyTimeout,
      maxChunkLineBytes = 32, maxTrailerBytes = 128
    ))
    def connect(): Socket = {
      val socket = new Socket()
      sockets += socket
      socket.connect(server.localAddress, 2000)
      // Size/cancel failures must close before the ten-second body deadline.
      socket.setSoTimeout(2000)
      socket
    }
    def startCompletion(socket: Socket, body: String): Unit = write(socket,
      "POST /auth/complete HTTP/1.1\r\nHost: localhost\r\nOrigin: http://localhost\r\n" +
        s"Cookie: binding=$binding\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n$body")
    def healthy(): Unit = {
      val socket = connect()
      write(socket, "GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n")
      val head = headers(socket)
      head should startWith("HTTP/1.1 200 ")
      head should include("Content-Length: 7\r\n")
      new String(socket.getInputStream.readNBytes(7), US_ASCII) shouldBe "healthy"
      socket.close()
    }
    def close(): Unit = {
      sockets.foreach(_.close())
      await(server.stopServingRequests())
    }
  }

  private def withServer(bodyTimeout: FiniteDuration = 10.seconds)(run: Fixture => Unit): Unit = {
    val fixture = new Fixture(bodyTimeout)
    try run(fixture)
    finally fixture.close()
  }
  private def write(socket: Socket, text: String): Unit = {
    socket.getOutputStream.write(text.getBytes(US_ASCII))
    socket.getOutputStream.flush()
  }
  private def headers(socket: Socket): String = {
    val value = new StringBuilder
    while (!value.toString.endsWith("\r\n\r\n") && value.length < 16384) {
      val next = socket.getInputStream.read()
      if (next < 0) fail("Connection closed before response headers")
      value.append(next.toChar)
    }
    value.toString should endWith("\r\n\r\n")
    value.toString
  }
  private def closedWithoutSuccess(socket: Socket): Unit = {
    val received = new ByteArrayOutputStream()
    var done = false
    while (!done && received.size() < 4096) {
      try {
        val next = socket.getInputStream.read()
        if (next < 0) done = true else received.write(next)
      } catch { case _: SocketException => done = true }
    }
    done shouldBe true
    val response = received.toString(US_ASCII)
    if (response.nonEmpty) {
      response should startWith("HTTP/1.1 ")
      response.split(' ')(1).toInt should be >= 400
      response should not include "Set-Cookie:"
    }
  }

  "Chunked authentication completion" should "close an oversized unterminated chunk-size line before its body deadline" in withServer() { fixture =>
    val socket = fixture.connect()
    fixture.startCompletion(socket, "0" * 33) // No CRLF, no body and no peer EOF.
    closedWithoutSuccess(socket)
    fixture.bodyBytes.get() shouldBe 0
    fixture.delivered.get() shouldBe 0
    fixture.healthy()
  }

  it should "stream the first 37 bytes of a huge chunk into the route and cancel without waiting for the rest" in withServer() { fixture =>
    val socket = fixture.connect()
    fixture.startCompletion(socket, "7fffffff\r\n" + "x" * 37)
    val head = headers(socket)
    head should startWith("HTTP/1.1 403 ")
    head should not include "Set-Cookie:"
    closedWithoutSuccess(socket)
    fixture.bodyBytes.get() shouldBe 37
    fixture.canceled.get() shouldBe 1
    fixture.delivered.get() shouldBe 0
    fixture.healthy()
  }

  it should "expire a valid-sized body without its terminating chunk before invoking delivery" in withServer(200.millis) { fixture =>
    val socket = fixture.connect()
    val handle = UUID.randomUUID().toString
    fixture.startCompletion(socket, s"24\r\n$handle\r\n") // Keep the peer open; omit 0 CRLF CRLF.
    closedWithoutSuccess(socket)
    fixture.bodyBytes.get() shouldBe 36
    fixture.delivered.get() shouldBe 0
    fixture.healthy()
  }

  it should "deliver a valid 36-byte chunked completion and continue accepting healthy connections" in withServer() { fixture =>
    val socket = fixture.connect()
    val handle = UUID.randomUUID()
    val text = handle.toString
    fixture.startCompletion(socket, s"12\r\n${text.take(18)}\r\n12\r\n${text.drop(18)}\r\n0\r\n\r\n")
    val head = headers(socket)
    head should startWith("HTTP/1.1 204 ")
    head should include(s"Set-Cookie: session=$token;")
    fixture.bodyBytes.get() shouldBe 36
    fixture.delivered.get() shouldBe 1
    fixture.deliveredHandle.get() shouldBe handle
    fixture.healthy()
  }
}
