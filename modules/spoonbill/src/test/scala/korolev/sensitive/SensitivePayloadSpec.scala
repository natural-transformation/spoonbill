package example.sensitive

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeCheckErrors
import spoonbill.sensitive.*

/** Outside spoonbill's package to exercise the actual consumer boundary. */
class SensitivePayloadSpec extends AnyFlatSpec with Matchers {
  private def accepted[A](result: Either[SensitiveError, A]): A = result.fold(error => fail(error.toString), identity)
  private val setup = "otpauth://totp/Example:synthetic?secret=JBSWY3DPEHPK3PXP&issuer=Example"

  "Sensitive payloads" should "accept bounded recovery text and local TOTP QR modules without diagnostic disclosure" in {
    val matrix = accepted(QrMatrix.fromRows(Vector.fill(21)("0" * 21)))
    val values: Vector[Any] = Vector(accepted(SensitivePayload.textList(Vector("synthetic-code-1", "synthetic-code-2"))),
      accepted(SensitivePayload.totpSetup(setup, Some(matrix))), matrix)
    values.foreach { value =>
      value.isInstanceOf[Product] shouldBe false
      value.isInstanceOf[java.io.Serializable] shouldBe false
      value.toString should include("redacted")
      value.toString should not include "synthetic"
      value.toString should not include "JBSWY"
    }
  }

  it should "enforce item count, UTF-8 byte bounds and cumulative retention bounds" in {
    SensitivePayload.textList(Vector.empty) shouldBe Left(SensitiveError.InvalidPayload)
    SensitivePayload.textList(Vector.fill(33)("code")) shouldBe Left(SensitiveError.PayloadTooLarge)
    SensitivePayload.textList(Vector("😀"), accepted(SensitiveLimits.create(maxTextItemUtf8Bytes = 3))) shouldBe
      Left(SensitiveError.PayloadTooLarge)
    SensitivePayload.textList(Vector("é", "é"), accepted(SensitiveLimits.create(maxUtf8Bytes = 3))) shouldBe
      Left(SensitiveError.PayloadTooLarge)
    SensitivePayload.textList(Vector("\uD800")) shouldBe Left(SensitiveError.InvalidPayload)
    SensitivePayload.textList(Vector("x" * 100000)) shouldBe Left(SensitiveError.PayloadTooLarge)
    SensitiveLimits.create(maxUtf8Bytes = 65537) shouldBe Left(SensitiveError.InvalidLimits)
    SensitiveLimits.create(maxTextItems = 0) shouldBe Left(SensitiveError.InvalidLimits)
  }

  it should "reject arbitrary links and malformed TOTP URI shapes without retaining rejected text" in {
    Vector("https://example.invalid/synthetic", "javascript:synthetic", "otpauth://hotp/x?secret=ABCD",
      "otpauth://totp/x", "otpauth://totp/x?secret=ABCD&secret=EFGH",
      "otpauth://user@totp/x?secret=ABCD", "otpauth://totp:443/x?secret=ABCD",
      "otpauth://totp/x?secret=not-valid!", "otpauth://totp/x?secret=ABCD#synthetic").foreach { uri =>
      SensitivePayload.totpSetup(uri) shouldBe Left(SensitiveError.InvalidTotpUri)
    }
    SensitivePayload.totpSetup(setup, limits = accepted(SensitiveLimits.create(maxUriUtf8Bytes = 8))) shouldBe
      Left(SensitiveError.PayloadTooLarge)
  }

  it should "bound QR geometry and include its retained modules in the payload budget" in {
    QrMatrix.fromRows(Vector.fill(20)("0" * 20)) shouldBe Left(SensitiveError.InvalidQrMatrix)
    QrMatrix.fromRows(Vector.fill(22)("0" * 22)) shouldBe Left(SensitiveError.InvalidQrMatrix)
    QrMatrix.fromRows(Vector.fill(181)("0" * 181)) shouldBe Left(SensitiveError.InvalidQrMatrix)
    QrMatrix.fromRows(Vector.fill(21)("1" * 20)) shouldBe Left(SensitiveError.InvalidQrMatrix)
    QrMatrix.fromRows(Vector.fill(21)("x" * 21)) shouldBe Left(SensitiveError.InvalidQrMatrix)
    val largest = accepted(QrMatrix.fromRows(Vector.fill(177)("1" * 177)))
    SensitivePayload.totpSetup(setup, Some(largest), accepted(SensitiveLimits.create(maxUtf8Bytes = 30000))) shouldBe
      Left(SensitiveError.PayloadTooLarge)
  }

  it should "exclude plaintext access, product derivation, and snapshot serialization at the consumer boundary" in {
    typeCheckErrors("""
      import spoonbill.sensitive.*
      def reveal(value: SensitivePayload.TextList) = value.items
    """) should not be empty
    typeCheckErrors("""
      import spoonbill.sensitive.*
      def reveal(value: SensitivePayload.TotpSetup) = value.uri
    """) should not be empty
    typeCheckErrors("""
      summon[scala.deriving.Mirror.ProductOf[spoonbill.sensitive.SensitivePayload.TextList]]
    """) should not be empty
    typeCheckErrors("""
      import spoonbill.snapshot.StateSchema
      case class Unsafe(value: spoonbill.sensitive.SensitivePayload) derives StateSchema
    """) should not be empty
    typeCheckErrors("""
      import spoonbill.snapshot.StateSchema
      case class Unsafe(value: Option[Vector[spoonbill.sensitive.QrMatrix]]) derives StateSchema
    """) should not be empty
    typeCheckErrors("""
      spoonbill.sensitive.PresentationId.fromUuid(java.util.UUID.randomUUID())
    """) should not be empty
    typeCheckErrors("""
      def reveal(value: spoonbill.sensitive.SensitiveDisclosure) = value.discard()
    """) should not be empty
    typeCheckErrors("""
      summon[spoonbill.snapshot.StateSchema[spoonbill.sensitive.SensitiveDisclosure]]
    """) should not be empty
    typeCheckErrors("""
      def bypass(payload: spoonbill.sensitive.SensitivePayload) = spoonbill.sensitive.SensitiveDisclosure.once(payload, null)
    """) should not be empty
    typeCheckErrors("""
      import spoonbill.sensitive.*
      import scala.concurrent.duration.*
      def bypass(region: RegionId, purpose: Purpose, payload: SensitivePayload) =
        spoonbill.action.UiOutcome.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => spoonbill.action.StateUpdate.unchanged)
    """) should not be empty
  }
}
