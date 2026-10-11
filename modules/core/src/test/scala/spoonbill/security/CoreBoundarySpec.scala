package spoonbill.security

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeChecks

class CoreBoundarySpec extends AnyFlatSpec with Matchers {
  "The core compilation classpath" should "exclude the runtime, effects, transport and persistence modules" in {
    typeChecks("val runtime: spoonbill.effect.Effect[Option] = null") shouldBe false
    typeChecks("val runtime: spoonbill.server.BrowserSessionToken = null") shouldBe false
    typeChecks("val runtime: spoonbill.auth.BrowserCapabilityScope[String] = null") shouldBe false
    typeChecks("val runtime: spoonbill.security.jdbc.JdbcBrowserSessions = null") shouldBe false
    typeChecks("val runtime: org.apache.pekko.actor.ActorSystem = null") shouldBe false
  }
}
