package zio.http.netty

import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.embedded.EmbeddedChannel
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class WebSocketFrameScanCharacterizationSpec extends AnyFlatSpec with Matchers {
  "Frame deadline observation" should "preserve buffer ownership and indexes across every extended-header split" in {
    for (length <- Seq(1, 126, 65536); masked <- Seq(false, true)) {
      val lengthBytes =
        if (length < 126) Array.emptyByteArray
        else if (length <= 65535)
          Array((length >> 8).toByte, length.toByte)
        else Array.tabulate[Byte](8)(index => (length.toLong >> ((7 - index) * 8)).toByte)
      val tag = if (length < 126) length else if (length <= 65535) 126 else 127
      val header = Array(0x82.toByte, (tag | (if (masked) 0x80 else 0)).toByte) ++ lengthBytes ++
        (if (masked) Array[Byte](1, 2, 3, 4) else Array.emptyByteArray)
      val wire = header ++ Array.fill[Byte](length)(7)
      for (split <- 1 to header.length) {
        val error   = new AtomicReference[Throwable]()
        val channel = new EmbeddedChannel(new WebSocketFrameDeadline(1000L, error.set, () => 0L))
        try {
          Seq(wire.take(split), wire.drop(split)).filter(_.nonEmpty).foreach { chunk =>
            val buffer = Unpooled.buffer(chunk.length + 1).writeByte(9).writeBytes(chunk)
            buffer.readerIndex(1)
            channel.writeInbound(buffer) shouldBe true
            val forwarded = channel.readInbound[ByteBuf]()
            forwarded should be theSameInstanceAs buffer
            forwarded.readerIndex() shouldBe 1
            forwarded.writerIndex() shouldBe chunk.length + 1
            forwarded.release()
          }
          error.get() shouldBe null
        } finally channel.finishAndReleaseAll()
      }
    }
  }

  it should "reject a negative extended length only after its complete masked header" in {
    val error   = new AtomicReference[Throwable]()
    val channel = new EmbeddedChannel(new WebSocketFrameDeadline(1000L, error.set, () => 0L))
    try {
      val prefix = Array[Byte](0x82.toByte, 0xff.toByte, 0x80.toByte, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3)
      channel.writeInbound(Unpooled.wrappedBuffer(prefix)) shouldBe true
      channel.readInbound[ByteBuf]().release()
      error.get() shouldBe null
      val last = Unpooled.wrappedBuffer(Array[Byte](4))
      channel.writeInbound(last) shouldBe false
      last.refCnt() shouldBe 0
      error.get().getMessage shouldBe "Invalid WebSocket frame length"
    } finally channel.finishAndReleaseAll()
  }
}
