package spoonbill.web

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class HeadersSpec extends AnyFlatSpec with Matchers {
  "setCookie" should "encode values symmetrically with request cookies" in {
    Seq("ordinary-id_0123", "base64+/==", "space ;percent%", "ü", "\r\nX-Canary: yes").foreach { value =>
      val (_, field) = Headers.setCookie("test-cookie", value, "/", 60)
      field should not include "\r"
      field should not include "\n"
      val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, (), field.takeWhile(_ != ';'))
      request.cookie("test-cookie") shouldBe Some(value)
    }
  }

  it should "reject cookie attribute injection through paths" in {
    Seq("/; Secure", "/\r\nX-Canary: yes", "/\t", "/\u0000").foreach { path =>
      intercept[IllegalArgumentException](Headers.setCookie("test", "value", path, 60))
    }
  }

  "header validation" should "accept normal redirects and permitted horizontal tabs" in {
    Headers.validate("Location", "https://app.example/path?q=x%20y")
    Headers.validate("X-Test", "one\ttwo")
  }

  it should "reject invalid names and prohibited control characters" in {
    Seq("", "X Test", "X:Test", "X\tTest", "X\r\nTest", "X-ü").foreach { name =>
      intercept[IllegalArgumentException](Headers.validate(name, "value"))
    }
    ((0 to 31).filterNot(_ == 9) :+ 127).foreach { control =>
      intercept[IllegalArgumentException](Headers.validate("X-Test", "before" + control.toChar + "after"))
    }
  }
}
