package spoonbill.http

import java.io.IOException
import java.util.concurrent.TimeoutException
import spoonbill.http.protocol.Http11

/** Identifies a failure actually observed while reading this request's input.
  * Exception classes alone must not suppress failures raised by application code.
  */
private[http] trait RequestInputFailure {
  def causedByInput(error: Throwable): Boolean
}

private[http] object RequestInputFailure {
  final class BodyDeadline extends TimeoutException("HTTP request body assembly expired")

  def expected(error: Throwable): Boolean = error match {
    case _: Http11.InvalidFraming | _: IOException | _: BodyDeadline => true
    case _ => false
  }
}
