package spoonbill.http.protocol

import spoonbill.data.BytesLike
import spoonbill.data.syntax.*
import spoonbill.effect.Decoder.Action
import spoonbill.web.Headers

/** A pure scan per input chunk. Only bounded framing metadata crosses calls;
  * payload passes through immediately, and byte scanning uses local variables.
  */
private[protocol] object ChunkedBodyDecoder {
  enum Phase { case Size, Payload, PayloadCR, PayloadLF, Trailers, Complete }
  final case class State(phase: Phase, remaining: Long = 0L, line: String = "", lineCR: Boolean = false,
    lineBytes: Int = 0, trailerBytes: Int = 0)
  val initial: State = State(Phase.Size)
  private val payloadCR: State = State(Phase.PayloadCR)
  private val payloadLF: State = State(Phase.PayloadLF)
  private val trailers: State = State(Phase.Trailers)
  val completed: State = State(Phase.Complete)
  private val forbiddenTrailers = Set("content-length", "transfer-encoding", "host", "connection", "upgrade", "trailer")

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

  inline def decode[B: BytesLike](state: State, incoming: B, maxLineBytes: Int, maxTrailerBytes: Int): (State, Action[B, B]) = {
    // Payload-only chunks have no framing metadata to reconstruct. Keep this
    // ordinary streaming edge independent of the bounded line scanner below.
    if (state.phase == Phase.Payload && incoming.length > 0L) {
      val count = math.min(state.remaining, incoming.length)
      val remaining = state.remaining - count
      val next = if (remaining == 0L) payloadCR else State(Phase.Payload, remaining)
      (next, Action.Fork(incoming.slice(0L, count), incoming.slice(count)))
    } else {
      var phase = state.phase
      var remaining = state.remaining
      var carriedLine = state.line
      var line: StringBuilder = null
      var lineCR = state.lineCR
      var lineBytes = state.lineBytes
      var trailerBytes = state.trailerBytes
      var index = 0L
      var scanning = true
      var action: Action[B, B] = Action.TakeNext
      while (index < incoming.length && scanning) {
        if (phase == Phase.Payload) {
          val count = math.min(remaining, incoming.length - index)
          remaining -= count
          if (remaining == 0) phase = Phase.PayloadCR
          // Never accumulate an advertised chunk: the route sees this slice now.
          val payload = incoming.slice(index, index + count)
          action = Action.Fork(payload, incoming.slice(index + count))
          scanning = false
        } else {
          val c = (incoming(index) & 0xff).toChar
          index += 1
          if (phase == Phase.PayloadCR) { check(c == '\r'); phase = Phase.PayloadLF }
          else if (phase == Phase.PayloadLF) { check(c == '\n'); phase = Phase.Size }
          else {
            check(phase == Phase.Size || phase == Phase.Trailers)
            check(lineBytes < (if (phase == Phase.Size) maxLineBytes else maxTrailerBytes))
            lineBytes += 1
            if (phase == Phase.Trailers) { check(trailerBytes < maxTrailerBytes); trailerBytes += 1 }
            if (lineCR) {
              check(c == '\n')
              val value = if (line == null) carriedLine else line.toString
              carriedLine = ""
              if (line != null) line.clear()
              lineCR = false
              lineBytes = 0
              if (phase == Phase.Size) {
                remaining = size(value)
                phase = if (remaining == 0) Phase.Trailers else Phase.Payload
              } else if (value.isEmpty) {
                phase = Phase.Complete
                action = Action.TakeBackFinish(incoming.slice(index))
                scanning = false
              } else {
                val colon = value.indexOf(':')
                check(colon > 0)
                val name = value.substring(0, colon)
                try Headers.validate(name, value.substring(colon + 1))
                catch { case _: IllegalArgumentException => check(false) }
                check(!forbiddenTrailers.contains(name.toLowerCase(java.util.Locale.ROOT)))
              }
            } else if (c == '\r') lineCR = true
            else {
              check(c != '\n')
              if (line == null) line = new StringBuilder(carriedLine)
              line.append(c)
            }
          }
        }
      }
      val next = phase match {
        case Phase.Payload => State(phase, remaining)
        case Phase.PayloadCR => payloadCR
        case Phase.PayloadLF => payloadLF
        case Phase.Size if lineBytes == 0 => initial
        case Phase.Trailers if trailerBytes == 0 => trailers
        case Phase.Complete => completed
        case _ => State(phase, remaining, if (line == null) carriedLine else line.toString, lineCR, lineBytes, trailerBytes)
      }
      (next, action)
    }
  }

  /** Compact thread-confined storage for the same pure transition. Copying the
    * result into scalar fields lets transient State/tuple records stay local,
    * instead of allocating a retained model object for every payload slice.
    */
  final class Cell[B: BytesLike](maxLineBytes: Int, maxTrailerBytes: Int) {
    require(maxLineBytes >= 3 && maxTrailerBytes >= 2, "Invalid chunk framing limits")
    private var phase = Phase.Size
    private var remaining = 0L
    private var line = ""
    private var lineCR = false
    private var lineBytes = 0
    private var trailerBytes = 0
    def completed: Boolean = phase == Phase.Complete

    def decode(incoming: B): Action[B, B] = {
      val result = ChunkedBodyDecoder.decode(State(phase, remaining, line, lineCR, lineBytes, trailerBytes),
        incoming, maxLineBytes, maxTrailerBytes)
      val next = result._1
      phase = next.phase
      remaining = next.remaining
      line = next.line
      lineCR = next.lineCR
      lineBytes = next.lineBytes
      trailerBytes = next.trailerBytes
      result._2
    }
  }
}
