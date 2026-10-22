package spoonbill.http.protocol

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.data.Bytes
import spoonbill.effect.Decoder
import spoonbill.http.protocol.WebSocketProtocol.Frame
import spoonbill.web.WebSocketLimits

final class WebSocketLimitsSpec extends AnyFlatSpec with Matchers {
  private val protocol = new WebSocketProtocol[Bytes](4)

  "Standalone decoder" should "reject declared lengths before waiting for the payload" in {
    for (
      header <- List(
                  Bytes(0x82, 5),
                  Bytes(0x82, 126, 0, 5),
                  Bytes(0x82, 127, 0, 0, 0, 0, 0, 0, 0, 5),
                  Bytes(0x82, 127, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
                )
    ) intercept[WebSocketLimits.Exceeded](protocol.decodeFrame(header))
  }

  it should "apply the default server budget to a 16385-byte declared payload" in {
    intercept[WebSocketLimits.Exceeded] {
      new WebSocketProtocol[Bytes]().decodeFrame(Bytes(0x82, 126, 0x40, 0x01))
    }
  }

  it should "accept exact boundary payloads" in {
    protocol.decodeFrame(Bytes(0x82, 4, 1, 2, 3, 4))._2 shouldBe
      Decoder.Action.Push(Frame.Binary(Bytes(1, 2, 3, 4)))
  }

  "Standalone message aggregation" should "reject cumulative fragments before append" in {
    val initial     = Frame.Text(Bytes(0xc3, 0xa9), fin = false)
    val (buffer, _) = protocol.mergeFrames(None, initial)
    protocol.mergeFrames(buffer, Frame.Continuation(Bytes(0xc3, 0xa9), fin = true))._2 shouldBe
      Decoder.Action.Push(Frame.Text(Bytes(0xc3, 0xa9, 0xc3, 0xa9)))
    intercept[WebSocketLimits.Exceeded] {
      protocol.mergeFrames(buffer, Frame.Continuation(Bytes(0xc3, 0xa9, 0x61), fin = true))
    }
  }
}
