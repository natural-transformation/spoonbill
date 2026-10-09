package spoonbill.internal

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * Transient event-time input. Never include values in errors or diagnostics.
 */
private[spoonbill] final class FormSubmission private (val fields: Vector[(String, String)]) {
  override def toString: String = "FormSubmission(<redacted>)"
}

private[spoonbill] object FormSubmission {
  val MaximumEncodedBytes = 65536
  val MaximumFields       = 64

  final case class InvalidSubmission() extends IllegalArgumentException("Invalid action submission")

  def decode(encoded: String): Either[InvalidSubmission, FormSubmission] =
    if (
      encoded.length > MaximumEncodedBytes ||
      encoded.getBytes(StandardCharsets.UTF_8).length > MaximumEncodedBytes ||
      encoded.count(_ == '&') >= MaximumFields
    )
      Left(InvalidSubmission())
    else if (encoded.isEmpty) Right(new FormSubmission(Vector.empty))
    else
      encoded
        .split("&", -1)
        .toVector
        .foldLeft[Either[InvalidSubmission, Vector[(String, String)]]](Right(Vector.empty)) { case (result, entry) =>
          result.flatMap { fields =>
            val separator = entry.indexOf('=')
            if (separator <= 0) Left(InvalidSubmission())
            else
              Try {
                val name  = URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(entry.substring(separator + 1), StandardCharsets.UTF_8)
                fields :+ (name -> value)
              }.toEither.left.map(_ => InvalidSubmission())
          }
        }
        .map(new FormSubmission(_))
}
