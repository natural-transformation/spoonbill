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
    private var bytes       = 0L
    private var pendingHigh = false

    def append(chunk: String): TextAccumulator = {
      var i = 0
      while (i < chunk.length) {
        val char = chunk.charAt(i)
        if (pendingHigh) {
          require(Character.isLowSurrogate(char), "Invalid UTF-16 in WebSocket text")
          pendingHigh = false
        } else if (Character.isHighSurrogate(char)) {
          pendingHigh = true
          bytes += 4
        } else {
          require(!Character.isLowSurrogate(char), "Invalid UTF-16 in WebSocket text")
          bytes += (if (char < 0x80) 1 else if (char < 0x800) 2 else 3)
        }
        check(bytes, maximum)
        i += 1
      }
      text.append(chunk)
      this
    }

    def result(): String = {
      require(!pendingHigh, "Incomplete UTF-16 in WebSocket text")
      text.toString
    }
  }
}
