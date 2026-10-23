package spoonbill.zio.http

import java.nio.charset.StandardCharsets.US_ASCII
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.server.BrowserSessionToken
import zio.{Unsafe, ZIO}

final class ZioCookieFieldsSpec extends AnyFlatSpec with Matchers {
  import ZioHttpKeepAliveFixture.*

  "ZIO raw Cookie fields" should "reject conflicting and malformed authentication fields before delivery" in {
    val deliveries = new AtomicInteger()
    val base = config()
    val configured = base.copy(authenticationCompletion = base.authenticationCompletion.map(_.copy(
      deliver = (_, binding) => ZIO.succeed {
        deliveries.incrementAndGet()
        if (binding == Binding) BrowserSessionToken.fromString(Token).toOption else None
      }
    )))
    val program = withServer(routes(configured)) { port =>
      RawHttpConnection.open(port).flatMap { connection =>
        def request(fields: Seq[String]) = connection.exchange("POST", "/auth/complete",
          Seq("Origin" -> Origin, "Content-Type" -> "text/plain") ++ fields.zipWithIndex.map { case (value, index) =>
            (if (index % 2 == 0) "Cookie" else "cOoKiE") -> value
          }, UUID.randomUUID().toString.getBytes(US_ASCII))
        for {
          single <- request(Seq(s"$BindingCookie=$Binding"))
          _ <- ZIO.succeed(single.status shouldBe 204)
          separate <- request(Seq(s"$BindingCookie=$Binding", s"$SessionCookie=$Token"))
          _ <- ZIO.succeed(separate.status shouldBe 204)
          _ <- ZIO.foreachDiscard(Seq(
            Seq(s"$BindingCookie=$Binding", s"$BindingCookie=" + "C" * 43),
            Seq(s"$BindingCookie=" + "C" * 43, s"$BindingCookie=$Binding"),
            Seq(s"$BindingCookie=$Binding", s"$BindingCookie=%GG"),
            Seq(s"$BindingCookie=$Binding", "%GG=value")
          )) { fields =>
            val before = deliveries.get()
            request(fields).map { response =>
              Set(400, 403) should contain(response.status)
              deliveries.get() shouldBe before
            }
          }
        } yield ()
      }
    }
    Unsafe.unsafe(implicit unsafe => runtime.unsafe.run(program).getOrThrowFiberFailure())
  }
}
