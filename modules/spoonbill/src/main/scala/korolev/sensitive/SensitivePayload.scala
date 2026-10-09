package spoonbill.sensitive

import java.net.{URI, URLDecoder}
import java.nio.CharBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.control.NonFatal

enum SensitiveError {
  case InvalidName, InvalidLimits, InvalidPayload, PayloadTooLarge, InvalidTotpUri, InvalidQrMatrix
  case InvalidDeadline, WrongBinding, DeadlineExceeded, NotExpired, InvalidTransition, Closed
}

final class SensitiveLimits private (
  val maxTextItems: Int,
  val maxTextItemUtf8Bytes: Int,
  val maxUtf8Bytes: Int,
  val maxUriUtf8Bytes: Int
)

object SensitiveLimits {
  val default: SensitiveLimits = new SensitiveLimits(32, 2048, 65536, 4096)

  def create(maxTextItems: Int = 32, maxTextItemUtf8Bytes: Int = 2048,
    maxUtf8Bytes: Int = 65536, maxUriUtf8Bytes: Int = 4096): Either[SensitiveError, SensitiveLimits] =
    if (maxTextItems <= 0 || maxTextItems > 64 || maxTextItemUtf8Bytes <= 0 || maxTextItemUtf8Bytes > 8192 ||
        maxUtf8Bytes <= 0 || maxUtf8Bytes > 65536 || maxUriUtf8Bytes <= 0 || maxUriUtf8Bytes > 8192)
      Left(SensitiveError.InvalidLimits)
    else Right(new SensitiveLimits(maxTextItems, maxTextItemUtf8Bytes, maxUtf8Bytes, maxUriUtf8Bytes))
}

/** A locally generated QR module matrix, not an image URL or executable markup.
  * The trusted encoder must ensure its content matches the intended disclosure.
  */
final class QrMatrix private (private[spoonbill] val rows: Vector[String]) {
  override def toString: String = "QrMatrix(<redacted>)"
}

object QrMatrix {
  def fromRows(rows: Vector[String]): Either[SensitiveError, QrMatrix] = {
    val size = rows.size
    if (size < 21 || size > 177 || (size - 21) % 4 != 0 ||
        !rows.forall(row => row != null && row.length == size && row.forall(c => c == '0' || c == '1')))
      Left(SensitiveError.InvalidQrMatrix)
    else Right(new QrMatrix(rows))
  }
}

/** Deliberate transient disclosure. These classes have no Product, Serializable,
  * extractor, snapshot schema, or public plaintext accessor. This prevents
  * accidental generic serialization, not retention by trusted application code
  * or reliable erasure of JVM/browser memory.
  */
sealed abstract class SensitivePayload private[sensitive] () {
  override final def toString: String = "SensitivePayload(<redacted>)"
}

object SensitivePayload {
  final class TextList private[sensitive] (private[spoonbill] val items: Vector[String]) extends SensitivePayload
  final class TotpSetup private[sensitive] (private[spoonbill] val uri: String,
    private[spoonbill] val qr: Option[QrMatrix]) extends SensitivePayload

  def textList(items: Vector[String], limits: SensitiveLimits = SensitiveLimits.default): Either[SensitiveError, SensitivePayload] =
    if (items.isEmpty) Left(SensitiveError.InvalidPayload)
    else if (items.size > limits.maxTextItems) Left(SensitiveError.PayloadTooLarge)
    else items.foldLeft[Either[SensitiveError, Int]](Right(0)) { (used, item) =>
      used.flatMap(total => utf8Size(item, limits.maxTextItemUtf8Bytes).flatMap { size =>
        if (total + size > limits.maxUtf8Bytes) Left(SensitiveError.PayloadTooLarge) else Right(total + size)
      })
    }.map(_ => new TextList(items))

  def totpSetup(uri: String, qr: Option[QrMatrix] = None,
    limits: SensitiveLimits = SensitiveLimits.default): Either[SensitiveError, SensitivePayload] =
    utf8Size(uri, limits.maxUriUtf8Bytes).flatMap { size =>
      val qrBytes = qr.fold(0)(matrix => matrix.rows.size * matrix.rows.size)
      if (size + qrBytes > limits.maxUtf8Bytes) Left(SensitiveError.PayloadTooLarge)
      else if (!validTotpUri(uri)) Left(SensitiveError.InvalidTotpUri)
      else Right(new TotpSetup(uri, qr))
    }

  private def utf8Size(value: String, limit: Int): Either[SensitiveError, Int] =
    if (value == null || value.isEmpty) Left(SensitiveError.InvalidPayload)
    else if (value.length > limit) Left(SensitiveError.PayloadTooLarge)
    else try {
      val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      val size = encoder.encode(CharBuffer.wrap(value)).remaining()
      if (size > limit) Left(SensitiveError.PayloadTooLarge) else Right(size)
    } catch { case NonFatal(_) => Left(SensitiveError.InvalidPayload) }

  private def validTotpUri(value: String): Boolean = try {
    val uri = new URI(value)
    val query = Option(uri.getRawQuery).toVector.flatMap(_.split('&').toVector)
    val secrets = query.flatMap { field =>
      val parts = field.split("=", 2)
      if (URLDecoder.decode(parts(0), StandardCharsets.UTF_8) == "secret")
        Some(if (parts.length == 2) URLDecoder.decode(parts(1), StandardCharsets.UTF_8) else "")
      else None
    }
    uri.getScheme == "otpauth" && uri.getHost == "totp" && uri.getRawUserInfo == null && uri.getPort == -1 &&
      uri.getRawFragment == null && Option(uri.getRawPath).exists(path => path.length > 1) &&
      secrets.size == 1 && secrets.head.matches("[A-Za-z2-7]+={0,6}")
  } catch { case NonFatal(_) => false }
}
