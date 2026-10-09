package spoonbill

import avocet.Id
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.internal.{FormSubmission, Frontend}

final class FormSubmissionSpec extends AnyFlatSpec with Matchers {
  "Event-time input" should "decode Unicode and delimiters without interpreting them as protocol fields" in {
    val parsed = FormSubmission.decode("email=synthetic%40example.invalid&password=a%3Ab%26c%3D%2B%C3%A9")
    parsed.map(_.fields) shouldBe Right(Vector("email" -> "synthetic@example.invalid", "password" -> "a:b&c=+é"))
  }

  it should "preserve duplicate fields for authoritative schema rejection" in {
    FormSubmission.decode("role=a&role=b").map(_.fields) shouldBe Right(Vector("role" -> "a", "role" -> "b"))
  }

  it should "reject malformed and oversized input without disclosing values" in {
    val sensitive = "synthetic-secret"
    val rejected  = FormSubmission.decode(s"password=$sensitive%ZZ")
    rejected.isLeft shouldBe true
    rejected.toString should not include sensitive
    FormSubmission.decode("password=" + "a" * FormSubmission.MaximumEncodedBytes).isLeft shouldBe true
    FormSubmission.decode(Vector.fill(65)("a=b").mkString("&")).isLeft shouldBe true
  }

  it should "redact retained input and event diagnostic representations" in {
    val submitted = FormSubmission.decode("password=synthetic-secret")
    submitted.toString should not include "synthetic-secret"
    Frontend.DomEventMessage(0, Id.TopLevel, "submit", Some(submitted)).toString should not include "synthetic-secret"
  }

  it should "reject malformed event headers without echoing submitted values" in {
    val synthetic = "synthetic-secret"
    val failure = intercept[IllegalArgumentException] {
      Frontend.decodeDomEvent(s"$synthetic:1_2:submit:password=$synthetic")
    }
    failure.toString should not include synthetic
    Frontend.decodeDomEvent("0:1_2:submit:password=synthetic-secret").toString should not include synthetic
  }
}
