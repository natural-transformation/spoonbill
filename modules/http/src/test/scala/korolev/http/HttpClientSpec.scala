package spoonbill.http

import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Flow, Source}
import java.io.ByteArrayInputStream
import java.net.{InetSocketAddress, URI}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.{KeyStore, SecureRandom}
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLServerSocket, SSLSocket, TrustManagerFactory}
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.{Try, Using}
import spoonbill.data.Bytes
import spoonbill.data.syntax._
import spoonbill.effect.{Queue, Reporter, Stream}
import spoonbill.effect.Reporter.PrintReporter
import spoonbill.effect.Reporter.PrintReporter.Implicit
import spoonbill.http.protocol.WebSocketProtocol.Frame
import spoonbill.web.{Headers, PathAndQuery, Request}
import spoonbill.web.PathAndQuery._
import spoonbill.web.Request.Method
import spoonbill.web.Response.Status

class HttpClientSpec extends AsyncFreeSpec {

  import HttpClientSpec._

  "HttpClient" - {
    "should properly send GET (content-length: 0) requests" in withServer(helloWorldRoute) { port =>
      for {
        client <- HttpClient.create[Future, Array[Byte]]()
        response <- client.http(
                      new InetSocketAddress("localhost", port),
                      request = Request(Method.Get, Root / "hello", Nil, Some(0), Stream.empty[Future, Array[Byte]])
                    )
        strictResponseBody <- response.body.fold(Array.empty[Byte])(_ ++ _)
        utf8Body            = strictResponseBody.asUtf8String
      } yield {
        assert(utf8Body.contains("Hello world") && response.status == Status.Ok)
      }
    }

    "should properly send GET requests over TLS" in withTlsServer { (port, context, group) =>
      for {
        client             <- HttpClient.create[Future, Array[Byte]](sslContext = context, group = group)
        response           <- client(Method.Get, URI.create(s"https://127.0.0.1:$port/hello"), Seq.empty, Some(0L), Stream.empty)
        strictResponseBody <- response.body.fold(Array.empty[Byte])(_ ++ _)
        utf8Body            = strictResponseBody.asUtf8String
      } yield {
        assert(utf8Body == "Hello over TLS" && response.status == Status.Ok)
      }
    }

    "should receive gzipped bodies well" in withServer(gzippedRoute) { port =>
      for {
        client <- HttpClient.create[Future, Array[Byte]]()
        response <- client.http(
                      new InetSocketAddress("localhost", port),
                      request = Request(
                        Method.Get,
                        Root / "gz",
                        Vector(Headers.AcceptEncoding -> "gzip"),
                        Some(0),
                        Stream.empty[Future, Array[Byte]]
                      )
                    )
        strictResponseBody <- response.body.fold(Array.empty[Byte])(_ ++ _)
        utf8Body            = uncompressByteArray(strictResponseBody).asUtf8String
      } yield {
        assert(utf8Body.contains("Hello world") && response.status == Status.Ok)
      }
    }

    "should receive chunked bodies well" in withServer(chunkedRoute) { port =>
      for {
        client <- HttpClient.create[Future, Array[Byte]]()
        response <- client.http(
                      address = new InetSocketAddress("localhost", port),
                      request = Request(
                        Method.Get,
                        Root / "chunked",
                        Vector.empty,
                        Some(0),
                        Stream.empty[Future, Array[Byte]]
                      )
                    )
        strictResponseBody <- response.body.fold(Array.empty[Byte])(_ ++ _)
        utf8Body            = strictResponseBody.asUtf8String
      } yield {
        assert(utf8Body.contains("123") && response.status == Status.Ok)
      }
    }

    "should properly send/receive WebSocket frames" in withServer(wsEchoRoute) { port =>

      val wsSample1 = Frame.Text(Bytes.wrap("Hello!".getBytes))
      val wsSample2 = Frame.Text(Bytes.wrap("I'm cow!".getBytes))

      for {
        client <- HttpClient.create[Future, Bytes]()
        queue  <- Future.successful(Queue[Future, Frame[Bytes]]())
        response <- client.webSocket(
                      address = new InetSocketAddress("localhost", port),
                      path = (Root / "echo"): PathAndQuery,
                      outgoingFrames = queue.stream,
                      cookie = Map.empty,
                      headers = Map.empty
                    )
        _     <- queue.offer(wsSample1)
        echo1 <- response.body.pull()
        _     <- queue.offer(wsSample2)
        echo2 <- response.body.pull()
        _     <- response.body.cancel()
        _     <- queue.close()
      } yield {
        assert(echo1.contains(wsSample1) && echo2.contains(wsSample2))
      }
    }

    "should accept response messages larger than the server command budget by default" in withServer(wsEchoRoute) {
      port =>
        val message = Frame.Text(Bytes.wrap(("x" * 20000).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        for {
          client <- HttpClient.create[Future, Bytes]()
          queue   = Queue[Future, Frame[Bytes]](1)
          response <- client.webSocket(
                        new InetSocketAddress("localhost", port),
                        Root / "echo",
                        queue.stream,
                        Map.empty,
                        Map.empty
                      )
          _    <- queue.offer(message)
          echo <- response.body.pull()
          _    <- response.body.cancel()
          _    <- queue.close()
        } yield assert(echo.contains(message))
    }

    "should apply a configured response byte limit at the decoder" in withServer(wsEchoRoute) { port =>
      for {
        client <- HttpClient.create[Future, Bytes](maxWebSocketMessageBytes = 4)
        queue   = Queue[Future, Frame[Bytes]](1)
        response <-
          client.webSocket(new InetSocketAddress("localhost", port), Root / "echo", queue.stream, Map.empty, Map.empty)
        exact = Frame.Text(Bytes.wrap("éé".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        _    <- queue.offer(exact)
        echo <- response.body.pull()
        _    <- queue.offer(Frame.Text(Bytes.wrap("ééa".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        rejected <-
          response.body.pull().map(_ => false).recover { case _: spoonbill.web.WebSocketLimits.Exceeded => true }
        _ <- queue.close()
      } yield assert(echo.contains(exact) && rejected)
    }

  }

}

object HttpClientSpec {

  import akka.actor.typed.ActorSystem
  import akka.actor.typed.scaladsl.Behaviors
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model._
  import akka.http.scaladsl.server.Directives._

  // Http server stubs

  val helloWorldRoute: Route =
    path("hello") {
      get {
        complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Hello world"))
      }
    }

  val gzippedRoute: Route =
    path("gz") {
      get {
        encodeResponse {
          complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Hello world"))
        }
      }
    }

  val chunkedRoute: Route =
    path("chunked") {
      get {
        val xs = Vector(
          HttpEntity.ChunkStreamPart("1"),
          HttpEntity.ChunkStreamPart("2"),
          HttpEntity.ChunkStreamPart("3")
        )
        complete(HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, Source(xs)))
      }
    }

  val wsEchoRoute: Route = {
    def echo: Flow[Message, Message, Any] =
      Flow[Message].mapConcat {
        case _: BinaryMessage => Nil
        case tm: TextMessage =>
          TextMessage(tm.textStream) :: Nil
      }
    path("echo") {
      handleWebSocketMessages(echo)
    }
  }

  // Utils

  /** Local certificate validation and encrypted I/O without external DNS or HTTPS. */
  def withTlsServer[T](f: (Int, SSLContext, AsynchronousChannelGroup) => Future[T]): Future[T] = {
    implicit val executionContext: ExecutionContext = ExecutionContext.global
    val password = "spoonbill-test-only".toCharArray
    val store = KeyStore.getInstance("PKCS12")
    Using.resource(getClass.getResourceAsStream("/tls/localhost.p12")) { input =>
      require(input != null, "Missing test-only localhost TLS fixture")
      store.load(input, password)
    }
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keys.init(store, password)
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trust.init(store)
    val serverContext = SSLContext.getInstance("TLS")
    serverContext.init(keys.getKeyManagers, null, new SecureRandom)
    val clientContext = SSLContext.getInstance("TLS")
    clientContext.init(null, trust.getTrustManagers, new SecureRandom)
    val listener = serverContext.getServerSocketFactory
      .createServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).asInstanceOf[SSLServerSocket]
    listener.setSoTimeout(5000)
    val accepted = new AtomicReference[SSLSocket]()
    val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    val timer = Executors.newSingleThreadScheduledExecutor()
    val deadline = Promise[T]()
    timer.schedule(new Runnable {
      def run(): Unit = { deadline.tryFailure(new TimeoutException("Local TLS test exceeded ten seconds")); () }
    }, 10L, TimeUnit.SECONDS)
    val server = Future(blocking {
      val socket = listener.accept().asInstanceOf[SSLSocket]
      accepted.set(socket)
      try {
        socket.setSoTimeout(5000)
        socket.startHandshake()
        val header = new StringBuilder
        while (!header.toString.endsWith("\r\n\r\n") && header.length < 8192) {
          val next = socket.getInputStream.read()
          require(next >= 0, "TLS client closed before sending request headers")
          header.append(next.toChar)
        }
        require(header.toString.startsWith("GET /hello HTTP/1.1\r\n") && header.toString.endsWith("\r\n\r\n"),
          "Unexpected or oversized local TLS request")
        val body = "Hello over TLS".getBytes(US_ASCII)
        socket.getOutputStream.write(s"HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n".getBytes(US_ASCII))
        socket.getOutputStream.write(body)
        socket.getOutputStream.flush()
      } finally socket.close()
    })
    val operation = Future.fromTry(Try(f(listener.getLocalPort, clientContext, group))).flatten
      .zip(server).map(_._1)
    Future.firstCompletedOf(Seq(operation, deadline.future)).transformWith { result =>
      Try(listener.close())
      Option(accepted.get()).foreach(socket => Try(socket.close()))
      Try(group.shutdownNow())
      timer.shutdownNow()
      Future.fromTry(result)
    }
  }

  def uncompressByteArray(from: Array[Byte]): Array[Byte] = {
    val stream = new GZIPInputStream(new ByteArrayInputStream(from.asArray), from.length)
    val buffer = new Array[Byte](50)
    var result = new Array[Byte](0)
    var n      = 0
    while ({
      { n = stream.read(buffer) };
      n > 0
    }) {
      result = result ++ buffer.slice(0, n)
    }
    result
  }

  def withServer[T](route: Route)(f: Int => Future[T]): Future[T] = {

    implicit val system           = ActorSystem(Behaviors.empty, "http-client-test-system")
    implicit val executionContext = system.executionContext

    // Bind on port 0 so the OS picks a free ephemeral port.
    // This avoids flaky "Address already in use" failures caused by random-port selection.
    Http()
      .newServerAt("localhost", 0)
      .bind(route)
      .flatMap { binding =>
        val port = binding.localAddress.getPort
        f(port).transformWith { tryResult =>
          for {
            _      <- binding.unbind()
            _       = system.terminate()
            result <- Future.fromTry(tryResult)
          } yield result
        }
      }
  }
}
