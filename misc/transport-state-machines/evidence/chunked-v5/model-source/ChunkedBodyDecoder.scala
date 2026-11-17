package spoonbill.http.protocol

import spoonbill.data.BytesLike
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

  def payloadEligible(phase: Phase, available: Long): Boolean = phase == Phase.Payload && available > 0L
  def payloadBytes(remaining: Long, available: Long): Long = math.min(remaining, available)
  def afterPayload(remaining: Long): Phase = if (remaining == 0L) Phase.PayloadCR else Phase.Payload

  /** A complete simple header needs only the next phase/count and output span.
    * Extensions, partial metadata and rejected input use the checked scan.
    */
  final case class Simple(phase: Phase, remaining: Long, from: Long, until: Long)

  def simple[B: BytesLike](phase: Phase, lineBytes: Int, lineCR: Boolean, incoming: B,
    maxLineBytes: Int, maxTrailerBytes: Int): Simple = {
    val bytes = BytesLike[B]
    val length = bytes.length(incoming)
    val prefix =
      if (lineBytes != 0 || lineCR) -1L
      else if (phase == Phase.Size) 0L
      else if (phase == Phase.PayloadCR && length >= 2L && bytes.get(incoming, 0L) == '\r' && bytes.get(incoming, 1L) == '\n') 2L
      else if (phase == Phase.PayloadLF && length >= 1L && bytes.get(incoming, 0L) == '\n') 1L
      else -1L
    if (prefix < 0L) null
    else {
      // A fast probe reads at most sixteen digits and never exceeds the line
      // budget. Longer zero prefixes and extensions retain the checked path.
      val limit = math.min(length - 2L, prefix + math.min(16L, maxLineBytes.toLong - 2L))
      var index = prefix
      var size = 0L
      var scanning = true
      var valid = true
      while (index < limit && scanning) {
        val c = bytes.get(incoming, index) & 0xff
        val digit = Character.digit(c.toChar, 16)
        if (c >= 128 || digit < 0) scanning = false
        else if (size > (Long.MaxValue - digit) / 16L) { valid = false; scanning = false }
        else { size = size * 16L + digit; index += 1L }
      }
      if (!valid || index <= prefix || index + 2L > length || index - prefix + 2L > maxLineBytes ||
        bytes.get(incoming, index) != '\r' || bytes.get(incoming, index + 1L) != '\n') null
      else {
        val from = index + 2L
        if (size == 0L) {
          if (maxTrailerBytes >= 2 && from + 2L <= length && bytes.get(incoming, from) == '\r' && bytes.get(incoming, from + 1L) == '\n')
            Simple(Phase.Complete, 0L, from + 2L, from + 2L)
          else null
        } else {
          val count = payloadBytes(size, length - from)
          if (count == 0L) null
          else Simple(afterPayload(size - count), size - count, from, from + count)
        }
      }
    }
  }

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

  def decode[B: BytesLike](state: State, incoming: B, maxLineBytes: Int, maxTrailerBytes: Int): (State, Action[B, B]) = {
    val bytes = BytesLike[B]
    val available = bytes.length(incoming)
    // Payload-only chunks have no framing metadata to reconstruct. Keep this
    // ordinary streaming edge independent of the bounded line scanner below.
    if (payloadEligible(state.phase, available)) {
      val count = payloadBytes(state.remaining, available)
      val remaining = state.remaining - count
      val next = if (afterPayload(remaining) == Phase.PayloadCR) payloadCR else State(Phase.Payload, remaining)
      return (next, Action.Fork(bytes.slice(incoming, 0L, count), bytes.slice(incoming, count, available)))
    }
    var phase = state.phase
    var remaining = state.remaining
    var carriedLine = state.line
    var line: StringBuilder = null
    var lineCR = state.lineCR
    var lineBytes = state.lineBytes
    var trailerBytes = state.trailerBytes
    inline def lineValue: String = if (line == null) carriedLine else line.toString
    inline def next: State = phase match {
      case Phase.Payload => State(phase, remaining)
      case Phase.PayloadCR => payloadCR
      case Phase.PayloadLF => payloadLF
      case Phase.Size if lineBytes == 0 => initial
      case Phase.Trailers if trailerBytes == 0 => trailers
      case Phase.Complete => completed
      case _ => State(phase, remaining, lineValue, lineCR, lineBytes, trailerBytes)
    }
    var index = 0L
    while (index < available) {
      if (phase == Phase.Payload) {
        val count = math.min(remaining, available - index)
        remaining -= count
        if (remaining == 0) phase = Phase.PayloadCR
        // Never accumulate an advertised chunk: the route sees this slice now.
        val payload = bytes.slice(incoming, index, index + count)
        return (next, Action.Fork(payload, bytes.slice(incoming, index + count, available)))
      }
      val c = (bytes.get(incoming, index) & 0xff).toChar
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
          val value = lineValue
          carriedLine = ""
          if (line != null) line.clear()
          lineCR = false
          lineBytes = 0
          if (phase == Phase.Size) {
            remaining = size(value)
            phase = if (remaining == 0) Phase.Trailers else Phase.Payload
          } else if (value.isEmpty) {
            return (completed, Action.TakeBackFinish(bytes.slice(incoming, index, available)))
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
    (next, Action.TakeNext)
  }

  /** Single-owner storage. Common payload/simple-header transitions return only
    * the scalars needed at that edge; checked metadata retains its immutable
    * state. The model owns both decisions, and tests compare both paths.
    */
  final class Cell[B](maxLineBytes: Int, maxTrailerBytes: Int)(using bytes: BytesLike[B]) {
    require(maxLineBytes >= 3 && maxTrailerBytes >= 2, "Invalid chunk framing limits")
    private var phase = Phase.Size
    private var remaining = 0L
    private var line = ""
    private var lineCR = false
    private var lineBytes = 0
    private var trailerBytes = 0
    def completed: Boolean = phase == Phase.Complete

    def decode(incoming: B): Action[B, B] = {
      val available = bytes.length(incoming)
      if (payloadEligible(phase, available)) {
        val count = payloadBytes(remaining, available)
        remaining -= count
        phase = afterPayload(remaining)
        Action.Fork(bytes.slice(incoming, 0L, count), bytes.slice(incoming, count, available))
      } else {
        val fast = simple(phase, lineBytes, lineCR, incoming, maxLineBytes, maxTrailerBytes)
        if (fast != null) {
          phase = fast.phase
          remaining = fast.remaining
          if (phase == Phase.Complete) Action.TakeBackFinish(bytes.slice(incoming, fast.until, available))
          else Action.Fork(bytes.slice(incoming, fast.from, fast.until), bytes.slice(incoming, fast.until, available))
        } else {
          val (next, action) = ChunkedBodyDecoder.decode(State(phase, remaining, line, lineCR, lineBytes, trailerBytes),
            incoming, maxLineBytes, maxTrailerBytes)
          phase = next.phase
          remaining = next.remaining
          line = next.line
          lineCR = next.lineCR
          lineBytes = next.lineBytes
          trailerBytes = next.trailerBytes
          action
        }
      }
    }
  }
}
