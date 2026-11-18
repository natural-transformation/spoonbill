package spoonbill.web

/**
 * Bounds wire message bytes independently of application protocol validation.
 */
object WebSocketLimits {
  val DefaultMaxMessageBytes: Int = 16 * 1024

  final class Exceeded extends IllegalArgumentException("WebSocket message exceeds the byte limit")

  def check(size: Long, maximum: Int): Unit =
    if (size < 0 || size > maximum) throw new Exceeded

  /**
   * Counts before appending, including surrogate pairs split between chunks. A
   * pending high surrogate reserves all four UTF-8 bytes of its code point.
   */
  final class TextAccumulator(maximum: Int) {
    require(maximum > 0, "maximum must be positive")
    private val text        = new java.lang.StringBuilder
    // This accumulator has one caller at a time. Store its pure model as
    // scalars so temporary input/output snapshots need not escape each append.
    private var bytes       = 0L
    private var pendingHigh = false

    private def applyMeasurement(measurement: WebSocketTextMachine.State): Unit = {
      bytes = measurement.bytes
      pendingHigh = measurement.pendingHigh
      measurement.outcome match {
        case WebSocketTextMachine.Outcome.Accepted => ()
        case WebSocketTextMachine.Outcome.InvalidUtf16 =>
          throw new IllegalArgumentException("requirement failed: Invalid UTF-16 in WebSocket text")
        case WebSocketTextMachine.Outcome.Exceeded => throw new Exceeded
      }
    }

    private def completeOrChecked(chunk: String): Unit = {
      if (WebSocketTextMachine.completeEligible(bytes, pendingHigh, chunk, maximum)) {
        val counted = WebSocketTextMachine.completeBytes(bytes, chunk, maximum)
        if (counted != WebSocketTextMachine.CheckedScanRequired) bytes = counted
        else applyMeasurement(WebSocketTextMachine.scan(bytes, pendingHigh, chunk, maximum))
      } else applyMeasurement(WebSocketTextMachine.scan(bytes, pendingHigh, chunk, maximum))
    }

    def append(chunk: String): TextAccumulator = {
      if (WebSocketTextMachine.asciiEligible(bytes, pendingHigh, chunk, maximum)) {
        val prefix = WebSocketTextMachine.asciiPrefix(chunk)
        val counted = WebSocketTextMachine.countedAscii(bytes, prefix)
        if (prefix == chunk.length) bytes = counted
        else applyMeasurement(WebSocketTextMachine.scanFrom(counted, false, chunk, prefix, maximum))
      } else completeOrChecked(chunk)
      text.append(chunk)
      this
    }

    def result(): String = {
      require(!pendingHigh, "Incomplete UTF-16 in WebSocket text")
      text.toString
    }
  }
}
