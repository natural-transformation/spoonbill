package spoonbill.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BrowserConfigurationSpec extends AnyFlatSpec with Matchers {
  private val valid =
    BrowserConfiguration(BrowserProviderScope("application", "session-v4"), Set("https://app.example"))

  "Browser configuration" should "accept exact HTTPS and explicitly insecure loopback origins" in {
    valid.errors shouldBe empty
    valid.copy(allowedOrigins = Set("http://localhost:8080"), secureCookies = false).errors shouldBe empty
    valid.copy(allowedOrigins = Set("http://127.0.0.1:8080"), secureCookies = false).errors shouldBe empty
  }

  it should "reject remote insecure origins, URI decorations, and ambiguous cookies" in {
    Seq(
      "https://app.example/",
      "https://user@app.example",
      "https://app.example?q=1",
      "https://app.example#fragment",
      "http://app.example",
      "https://app.example:0",
      "null"
    ).foreach { origin =>
      valid.copy(allowedOrigins = Set(origin)).errors should not be empty
    }
    valid.copy(secureCookies = false).errors should not be empty
    valid.copy(sessionCookieName = valid.bindingCookieName).errors should not be empty
    valid.copy(bindingCookieName = "deviceId").errors should not be empty
    valid.copy(bindingCookieName = "bad;cookie").errors should not be empty
    valid.copy(cookieMaxAgeSeconds = 0).errors should not be empty
    valid.copy(maxPendingOperations = 0).errors should not be empty
  }

  it should "allow only origin-relative logout destinations without changing its input" in {
    Seq("https://other.example", "//other.example", "/\\other.example", "/line\r\nheader", "relative").foreach { path =>
      valid.copy(logoutRedirect = path).errors should not be empty
    }
    valid.copy(logoutRedirect = "/signed-out?source=account").errors shouldBe empty
    valid.logoutRedirect shouldBe "/"
    valid.errors shouldBe empty
  }
}
