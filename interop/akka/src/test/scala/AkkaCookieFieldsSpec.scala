package spoonbill.akka

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.Materializer
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.Request

final class AkkaCookieFieldsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("akka-cookie-fields")
  private implicit val materializer: Materializer = Materializer(system)
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private val timeout = 8.seconds
  override def afterAll(): Unit =
    try Await.result(system.terminate(), timeout)
    finally super.afterAll()

  "Akka raw Cookie fields" should "reject conflicting and malformed authentication fields before delivery" in {
    val deliveries = new AtomicInteger()
    val expected = "B" * 43
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
      def open(qsid: Qsid, request: Request.Head, id: ConnectionId): Future[SessionGuard[Future, String]] =
        Future.failed(new IllegalStateException("Unused WebSocket"))
    }
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("public"),
      sessionAccessControl = Some(control),
      authenticationCompletion = Some(AuthenticationCompletionConfig[Future](
        "binding", "session", Set("http://localhost"), false, 60,
        (_, binding) => Future.successful {
          deliveries.incrementAndGet()
          if (binding == expected) BrowserSessionToken.fromString("T" * 43).toOption else None
        },
        (_, _) => Future.unit
      ))
    )
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0)
      .bind(akkaHttpService(config).apply(AkkaHttpServerConfig())), timeout)
    def request(cookies: Seq[String]): Int = {
      val socket = new Socket()
      try {
        socket.connect(new InetSocketAddress("127.0.0.1", binding.localAddress.getPort), 3000)
        socket.setSoTimeout(5000)
        val body = UUID.randomUUID().toString
        val fields = cookies.zipWithIndex.map { case (value, index) =>
          s"${if (index % 2 == 0) "Cookie" else "cOoKiE"}: $value\r\n"
        }.mkString
        socket.getOutputStream.write(
          (s"POST /auth/complete HTTP/1.1\r\nHost: localhost\r\nOrigin: http://localhost\r\n" +
            s"Content-Type: text/plain\r\nContent-Length: 36\r\n$fields\r\n$body").getBytes(US_ASCII))
        socket.getOutputStream.flush()
        val head = new StringBuilder
        while (!head.toString.endsWith("\r\n\r\n") && head.length < 16384) {
          val next = socket.getInputStream.read()
          if (next < 0) fail("Incomplete authentication response")
          head.append(next.toChar)
        }
        head.toString.split(' ')(1).toInt
      } finally socket.close()
    }
    try {
      request(Seq(s"binding=$expected")) shouldBe 204
      request(Seq(s"binding=$expected", "session=" + "S" * 43)) shouldBe 204
      Seq(
        Seq(s"binding=$expected", "binding=" + "C" * 43),
        Seq("binding=" + "C" * 43, s"binding=$expected"),
        Seq(s"binding=$expected", "binding=%GG"),
        Seq(s"binding=$expected", "%GG=value")
      ).foreach { fields =>
        val before = deliveries.get()
        Set(400, 403) should contain(request(fields))
        deliveries.get() shouldBe before
      }
    } finally Await.result(binding.terminate(2.seconds), timeout)
  }
}
