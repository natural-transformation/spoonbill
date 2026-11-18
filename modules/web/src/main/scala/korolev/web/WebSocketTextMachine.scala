package spoonbill.web

import scala.annotation.tailrec

/** Counts a text chunk without retaining its characters or allocating per character. */
private[spoonbill] object WebSocketTextMachine {
  enum Outcome { case Accepted, InvalidUtf16, Exceeded }
  final case class State(bytes: Long = 0L, pendingHigh: Boolean = false, outcome: Outcome = Outcome.Accepted)
  val initial: State = State()
  val CheckedScanRequired: Long = -1L

  def completeEligible(bytes: Long, pendingHigh: Boolean, chunk: String, maximum: Int): Boolean =
    !pendingHigh && bytes >= 0 && chunk.length.toLong <= maximum.toLong - bytes

  /** A complete valid chunk needs only its final count; partial/error states use scan. */
  def completeBytes(bytes: Long, chunk: String, maximum: Int): Long = {
    @tailrec def loop(index: Int, total: Long, pendingHigh: Boolean): Long =
      if (index == chunk.length) {
        if (pendingHigh) CheckedScanRequired else total
      }
      else {
        val char = chunk.charAt(index)
        if (pendingHigh) {
          // The high half reserved and checked all four bytes already.
          if (Character.isLowSurrogate(char)) loop(index + 1, total, false) else CheckedScanRequired
        } else if (Character.isHighSurrogate(char)) {
          val next = total + 4
          if (next > maximum) CheckedScanRequired else loop(index + 1, next, true)
        } else if (Character.isLowSurrogate(char)) CheckedScanRequired
        else {
          val next = total + (if (char < 0x80) 1 else if (char < 0x800) 2 else 3)
          if (next > maximum) CheckedScanRequired
          else loop(index + 1, next, false)
        }
      }
    if (bytes < 0 || bytes > maximum) CheckedScanRequired else loop(0, bytes, false)
  }

  /** ASCII can be counted as one chunk when even its complete length fits. */
  def asciiEligible(bytes: Long, pendingHigh: Boolean, chunk: String, maximum: Int): Boolean =
    !pendingHigh && bytes >= 0 && (chunk.isEmpty || chunk.charAt(0) < 0x80) &&
      chunk.length.toLong <= maximum.toLong - bytes

  def asciiPrefix(chunk: String): Int = {
    @tailrec def loop(index: Int): Int =
      if (index == chunk.length || chunk.charAt(index) >= 0x80) index else loop(index + 1)
    loop(0)
  }

  def countedAscii(bytes: Long, characters: Int): Long = bytes + characters

  /** A rejected chunk keeps its counted prefix, matching the accumulator contract. */
  def scan(state: State, chunk: String, maximum: Int): State =
    if (chunk.isEmpty && state.outcome == Outcome.Accepted) state
    else scan(state.bytes, state.pendingHigh, chunk, maximum)

  /** Scalar inputs let the single-owner interpreter avoid an input snapshot allocation. */
  def scan(bytes: Long, pendingHigh: Boolean, chunk: String, maximum: Int): State =
    scanFrom(bytes, pendingHigh, chunk, 0, maximum)

  /** Continue after a proven ASCII prefix without scanning those characters twice. */
  def scanFrom(bytes: Long, pendingHigh: Boolean, chunk: String, from: Int, maximum: Int): State = {
    @tailrec def loop(index: Int, bytes: Long, pendingHigh: Boolean): State = {
      if (index == chunk.length) State(bytes, pendingHigh)
      else {
        val char = chunk.charAt(index)
        if (pendingHigh) {
          if (!Character.isLowSurrogate(char)) State(bytes, true, Outcome.InvalidUtf16)
          else if (bytes < 0 || bytes > maximum) State(bytes, false, Outcome.Exceeded)
          else loop(index + 1, bytes, false)
        } else if (Character.isHighSurrogate(char)) {
          val next = bytes + 4
          if (next < 0 || next > maximum) State(next, true, Outcome.Exceeded)
          else loop(index + 1, next, true)
        } else if (Character.isLowSurrogate(char)) State(bytes, false, Outcome.InvalidUtf16)
        else {
          val next = bytes + (if (char < 0x80) 1 else if (char < 0x800) 2 else 3)
          if (next < 0 || next > maximum) State(next, false, Outcome.Exceeded)
          else loop(index + 1, next, false)
        }
      }
    }
    loop(math.max(0, math.min(from, chunk.length)), bytes, pendingHigh)
  }
}
