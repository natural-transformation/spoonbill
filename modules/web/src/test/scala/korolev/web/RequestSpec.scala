package spoonbill.web

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.web.Request.Method

class RequestSpec extends AnyFlatSpec with Matchers {

  private val defaultRequest = Request(Method.Get, PathAndQuery.Root, Nil, None, ())

  "parsedCookies" should "parse cookies in correct format" in {
    val request = defaultRequest.copy(renderedCookie = "a=1;b=2")
    request.cookie("a") shouldEqual Some("1")
    request.cookie("b") shouldEqual Some("2")
  }

  it should "parse cookies without values" in {
    val request = defaultRequest.copy(renderedCookie = "a;b=2")
    request.cookie("a") shouldEqual Some("")
    request.cookie("b") shouldEqual Some("2")
  }

  it should "preserve equals signs inside cookie values" in {
    val request = defaultRequest.copy(renderedCookie = "a=1=2;b=2")
    request.cookie("a") shouldEqual Some("1=2")
    request.cookie("b") shouldEqual Some("2")
  }

  it should "ignore malformed escapes without breaking unrelated cookies" in {
    Seq("%", "%0", "%GG").foreach { malformed =>
      val request = defaultRequest.copy(renderedCookie = s"bad=$malformed;good=ok")
      request.cookie("bad") shouldBe None
      request.cookie("good") shouldBe Some("ok")
    }
  }

  it should "reject duplicate decoded names even when one value is malformed" in {
    Seq("a=first;a=second", "a=first;%61=second", "a=%GG;a=second", "a=first;a=%GG").foreach { raw =>
      defaultRequest.copy(renderedCookie = raw).cookie("a") shouldBe None
    }
  }

  it should "decode once and preserve encoded delimiters as data" in {
    val request = defaultRequest.copy(renderedCookie = "a=x%3by%3Dz%2B%25;b=%0d%0A;c=%250d%250a")
    request.cookie("a") shouldBe Some("x;y=z+%")
    request.cookie("b") shouldBe Some("\r\n")
    request.cookie("c") shouldBe Some("%0d%0a")
  }

  "withCookie" should "replace all matching encoded names and round trip values" in {
    val request = defaultRequest
      .copy(renderedCookie = "a=first;%61=second;b=untouched")
      .withCookie("a", "plus+ space ;equals=percent% and ü")
    request.cookie("a") shouldBe Some("plus+ space ;equals=percent% and ü")
    request.cookie("b") shouldBe Some("untouched")
  }
}
