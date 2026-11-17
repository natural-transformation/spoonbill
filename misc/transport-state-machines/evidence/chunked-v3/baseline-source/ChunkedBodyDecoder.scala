package spoonbill.http.protocol

import spoonbill.data.BytesLike
import spoonbill.data.syntax.*
import spoonbill.effect.Decoder.Action
import spoonbill.web.Headers

/** Retains bounded framing metadata only; payload passes through as it arrives. */
private[protocol] final class ChunkedBodyDecoder[B: BytesLike](maxLineBytes: Int, maxTrailerBytes: Int) {
  require(maxLineBytes >= 3 && maxTrailerBytes >= 2, "Invalid chunk framing limits")
  private var phase = 0 // size line, payload, payload CR, payload LF, trailers, complete
  private var remaining = 0L
  private val line = new StringBuilder
  private var lineCR = false
  private var lineBytes = 0
  private var trailerBytes = 0
  def completed: Boolean = phase == 5

  private def check(valid: Boolean): Unit =
    if (!valid) throw new Http11.InvalidFraming("Invalid or oversized chunk framing")

  private def size(value: String): Long = {
    var i = 0
    var length = 0L
    while (i < value.length && Character.digit(value.charAt(i), 16) >= 0) {
      val digit = Character.digit(value.charAt(i), 16)
      check(value.charAt(i) < 128 && length <= (Long.MaxValue - digit) / 16)
      length = length * 16 + digit
      i += 1
    }
    check(i > 0)
    def whitespace(): Unit = while (i < value.length && (value.charAt(i) == ' ' || value.charAt(i) == '\t')) i += 1
    def token(): Unit = {
      val begin = i
      while (i < value.length && isToken(value.charAt(i))) i += 1
      check(i > begin)
    }
    // Ignore unknown extensions, but parse their delimiters and quoted values.
    while (i < value.length) {
      whitespace()
      check(i < value.length && value.charAt(i) == ';')
      i += 1
      whitespace()
      token()
      val nameEnd = i
      whitespace()
      check(i < value.length || i == nameEnd)
      if (i < value.length && value.charAt(i) == '=') {
        i += 1
        whitespace()
        check(i < value.length)
        if (value.charAt(i) == '"') {
          i += 1
          var ended = false
          while (i < value.length && !ended) {
            val c = value.charAt(i)
            i += 1
            if (c == '"') ended = true
            else if (c == '\\') {
              check(i < value.length && (value.charAt(i) == '\t' || (value.charAt(i) >= ' ' && value.charAt(i) != 127)))
              i += 1
            } else check(c == '\t' || (c >= ' ' && c != 127))
          }
          check(ended)
        } else token()
      }
    }
    length
  }

  private def isToken(c: Char): Boolean =
    c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || "!#$%&'*+-.^_`|~".contains(c)

  def decode(incoming: B): Action[B, B] = {
    var index = 0L
    while (index < incoming.length) {
      if (phase == 1) {
        val count = math.min(remaining, incoming.length - index)
        remaining -= count
        if (remaining == 0) phase = 2
        // Never accumulate an advertised chunk: the route sees this slice now.
        val payload = incoming.slice(index, index + count)
        return Action.Fork(payload, incoming.slice(index + count))
      }
      val c = (incoming(index) & 0xff).toChar
      index += 1
      if (phase == 2) { check(c == '\r'); phase = 3 }
      else if (phase == 3) { check(c == '\n'); phase = 0 }
      else {
        check(phase == 0 || phase == 4)
        check(lineBytes < (if (phase == 0) maxLineBytes else maxTrailerBytes))
        lineBytes += 1
        if (phase == 4) { check(trailerBytes < maxTrailerBytes); trailerBytes += 1 }
        if (lineCR) {
          check(c == '\n')
          val value = line.toString
          line.clear()
          lineCR = false
          lineBytes = 0
          if (phase == 0) {
            remaining = size(value)
            phase = if (remaining == 0) 4 else 1
          } else if (value.isEmpty) {
            phase = 5
            return Action.TakeBackFinish(incoming.slice(index))
          } else {
            val colon = value.indexOf(':')
            check(colon > 0)
            val name = value.substring(0, colon)
            try Headers.validate(name, value.substring(colon + 1))
            catch { case _: IllegalArgumentException => check(false) }
            check(!Set("content-length", "transfer-encoding", "host", "connection", "upgrade", "trailer")
              .contains(name.toLowerCase(java.util.Locale.ROOT)))
          }
        } else if (c == '\r') lineCR = true
        else { check(c != '\n'); line.append(c) }
      }
    }
    Action.TakeNext
  }
}
