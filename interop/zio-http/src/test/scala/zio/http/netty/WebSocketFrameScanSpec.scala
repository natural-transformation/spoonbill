package zio.http.netty

import io.netty.buffer.Unpooled
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class WebSocketFrameScanSpec extends AnyFlatSpec with Matchers {
  import WebSocketFrameScan.*

  "Frame scan" should "stop at each frame boundary without consuming the following frame" in {
    val wire = Unpooled.wrappedBuffer(Array[Byte](0x82.toByte, 1, 7, 0x82.toByte, 0))
    try {
      val first = scan(initial, wire, 0)
      first.boundary shouldBe Boundary.Complete
      first.index shouldBe 3
      val second = scan(initial, wire, first.index)
      second.boundary shouldBe Boundary.Complete
      second.index shouldBe 5
      wire.readerIndex() shouldBe 0
    } finally wire.release()
  }

  it should "retain a long payload count without retaining any payload buffer" in {
    val header = Unpooled.wrappedBuffer(Array[Byte](0x82.toByte, 127, 0, 0, 0, 1, 0, 0, 0, 0))
    try {
      val next = scan(initial, header, 0)
      next.boundary shouldBe Boundary.More
      next.state.payloadRemaining shouldBe 4294967296L
      next.state.readingPayload shouldBe true
      initial shouldBe State(0, 2, 0, 0L, false)
    } finally header.release()
  }

  "Frame timer" should "reject the absolute boundary and accept completion strictly before it" in {
    import WebSocketFrameTimer as Timer
    val armed = Timer.begin(7L)
    Timer.complete(armed, 16L, 10L) shouldBe Timer.State.Idle
    Timer.complete(armed, 17L, 10L) shouldBe Timer.State.Failed
    Timer.complete(Timer.State.Failed, 8L, 10L) shouldBe Timer.State.Failed
  }
}
