package zio.http.netty

import io.netty.buffer.ByteBuf

/**
 * Read-only wire scan. The event-loop interpreter observes each frame boundary
 * before scanning the following frame, so deadline effects keep their order.
 * Length metadata is bounded; payload is skipped without retention or copying.
 */
private[zio] object WebSocketFrameScan {
  final case class State(
    headerBytes: Int,
    headerLength: Int,
    lengthTag: Int,
    payloadRemaining: Long,
    readingPayload: Boolean
  )
  val initial: State = State(0, 2, 0, 0L, false)
  enum Boundary { case More, Complete, Fragmented, InvalidLength }
  final case class Result(state: State, index: Int, boundary: Boundary)

  inline def scan(state: State, bytes: ByteBuf, start: Int): Result = {
    var headerBytes      = state.headerBytes
    var headerLength     = state.headerLength
    var lengthTag        = state.lengthTag
    var payloadRemaining = state.payloadRemaining
    var readingPayload   = state.readingPayload
    var index            = start
    var boundary         = Boundary.More
    val end              = bytes.writerIndex()
    while (index < end && boundary == Boundary.More) {
      if (readingPayload) {
        val count = math.min(payloadRemaining, (end - index).toLong).toInt
        payloadRemaining -= count
        index += count
        if (payloadRemaining == 0) boundary = Boundary.Complete
      } else {
        val value = bytes.getByte(index) & 0xff
        headerBytes += 1
        index += 1
        if (headerBytes == 1 && ((value & 0x80) == 0 || (value & 0x0f) == 0))
          boundary = Boundary.Fragmented
        if (headerBytes == 2) {
          lengthTag = value & 0x7f
          headerLength = 2 + (if (lengthTag == 126) 2 else if (lengthTag == 127) 8 else 0) +
            (if ((value & 0x80) != 0) 4 else 0)
          payloadRemaining = if (lengthTag < 126) lengthTag.toLong else 0L
        } else if (
          headerBytes > 2 && headerBytes <= 2 + (if (lengthTag == 126) 2 else if (lengthTag == 127) 8 else 0)
        ) {
          payloadRemaining = (payloadRemaining << 8) | value.toLong
        }
        if (headerBytes == headerLength) {
          if (payloadRemaining < 0) boundary = Boundary.InvalidLength
          else if (payloadRemaining == 0) boundary = Boundary.Complete
          else readingPayload = true
        }
      }
    }
    Result(State(headerBytes, headerLength, lengthTag, payloadRemaining, readingPayload), index, boundary)
  }
}
