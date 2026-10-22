package spoonbill.server

import avocet.dsl.*
import avocet.dsl.html.*
import java.net.{InetSocketAddress, Socket, SocketAddress}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.auth.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.state.javaSerialization.*
import spoonbill.web.Response

/**
 * Exercise request decoding, bootstrap, cookie output, and response bytes on a
 * real socket.
 */
final class StandaloneHeaderSafetySpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future]   = Effect.futureEffect

  "unsafe application response headers" should "close without emitting bytes and release the response body" in {
    val released = Promise[Unit]()
    val body = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = Future.failed(new AssertionError("Invalid response body must not be pulled"))
      def cancel(): Future[Unit]        = { released.trySuccess(()); Future.unit }
    }
    val service = new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] = Future.successful(
        Response(Response.Status.Ok, body, Seq("X-Unsafe" -> "value\r\nX-Spoonbill-Canary: present"), Some(1L))
      )
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] =
        Future.failed(new IllegalStateException("unused"))
    }
    val handler = Await.result(
      standalone
        .buildServer[Future, Array[Byte]](service, new InetSocketAddress("127.0.0.1", 0), gracefulShutdown = false),
      10.seconds
    )
    val socket = new Socket()
    try {
      socket.connect(handler.localAddress, 3000)
      socket.setSoTimeout(5000)
      socket.getOutputStream.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
      socket.getOutputStream.flush()
      socket.getInputStream.read() shouldBe -1
      Await.result(released.future, 3.seconds)
    } finally {
      socket.close()
      Await.result(handler.stopServingRequests(), 10.seconds)
    }
  }

  private def rawHeaders(address: SocketAddress, cookieFields: Seq[String]): String = {
    val socket = new Socket()
    try {
      socket.connect(address, 3000)
      socket.setSoTimeout(5000)
      val cookies = cookieFields.map(value => s"Cookie: $value\r\n").mkString
      socket.getOutputStream.write(
        (s"GET / HTTP/1.1\r\nHost: localhost\r\n${cookies}\r\n").getBytes(StandardCharsets.US_ASCII)
      )
      socket.getOutputStream.flush()
      val result = new StringBuilder
      while (!result.toString.endsWith("\r\n\r\n") && result.length < 16384) {
        val next = socket.getInputStream.read()
        if (next == -1) fail("Response closed before the HTTP headers completed")
        result.append(next.toChar)
      }
      result.toString
    } finally socket.close()
  }

  Seq(false, true).foreach { guarded =>
    "standalone response headers" should s"replace unsafe device cookies through ${if (guarded) "guarded"
    else "unguarded"} bootstrap" in {
      val configuration = BrowserConfiguration(
        BrowserProviderScope("headers-test", "browser"),
        Set("http://localhost"),
        secureCookies = false
      )
      val provider = new InMemoryBrowserPersistence[Future, Unit, String](
        (),
        new InMemoryBrowserPersistence.Host[Unit, String] {
          def resolve(state: Unit, sessionId: UUID, now: Instant): Option[HostPrincipal[String]] = None
        },
        () => Instant.now(),
        () => UUID.randomUUID(),
        scope = configuration.scope
      )
      val browser = new BrowserAuthentication[Future, String, String](
        provider,
        BrowserPresentation(() => "public", (_, _) => true, (state, _, _) => state),
        configuration
      )
      val base = SpoonbillServiceConfig[Future, String, Any](
        stateLoader = StateLoader.default[Future, String]("public"),
        document = state => Html(body(div(state)))
      )
      val config = if (guarded) browser.configure(base) else base
      val handler = Await.result(
        standalone.buildServer[Future, Array[Byte]](
          spoonbillService(config),
          new InetSocketAddress("127.0.0.1", 0),
          gracefulShutdown = false
        ),
        10.seconds
      )
      try {
        val cases = Seq(
          Seq("deviceId=canary%0D%0AX-Spoonbill-Canary%3A%20present"),
          Seq("deviceId=canary%0d%0aX-Spoonbill-Canary%3a+present"),
          Seq("deviceId=%GG"),
          Seq("deviceId=" + "x" * 129),
          Seq("deviceId=x%3BPath%3D%2Fevil"),
          Seq("deviceId=first;deviceId=second"),
          Seq("deviceId=first", "deviceId=second")
        )
        cases.foreach { cookies =>
          val raw = rawHeaders(handler.localAddress, cookies)
          raw should startWith("HTTP/1.1 200 ")
          raw should not include "X-Spoonbill-Canary"
          val fields = raw.split("\r\n").filter(_.startsWith("Set-Cookie: "))
          fields.count(_.startsWith("Set-Cookie: deviceId=")) shouldBe 1
          val id = fields
            .find(_.startsWith("Set-Cookie: deviceId="))
            .get
            .stripPrefix("Set-Cookie: deviceId=")
            .takeWhile(_ != ';')
          id should fullyMatch regex "[A-Za-z0-9]{16}"
          if (guarded) fields.count(_.startsWith(s"Set-Cookie: ${configuration.bindingCookieName}=")) shouldBe 1
        }
        val valid = rawHeaders(handler.localAddress, Seq("deviceId=custom_0123-UUID.id"))
        valid should startWith("HTTP/1.1 200 ")
        valid should include("Set-Cookie: deviceId=custom_0123-UUID.id;")
      } finally {
        Await.result(handler.stopServingRequests(), 10.seconds)
        Await.result(browser.close(), 10.seconds)
      }
    }
  }
}
