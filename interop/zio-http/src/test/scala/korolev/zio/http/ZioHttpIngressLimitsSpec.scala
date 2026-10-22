package spoonbill.zio.http

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.websocketx.{
  BinaryWebSocketFrame,
  CorruptedWebSocketFrameException,
  WebSocket13FrameDecoder
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.data.Bytes
import spoonbill.effect.{Queue, Reporter}
import spoonbill.zio.Zio2Effect
import zio.{Exit, Ref, RIO, Runtime, Unsafe, ZIO}
import zio.http.{ChannelEvent, WebSocketFrame}
import zio.stream.ZStream

final class ZioHttpIngressLimitsSpec extends AnyFlatSpec with Matchers {
  private type Task[A] = RIO[Any, A]
  implicit private val effect: Zio2Effect[Any, Throwable] =
    new Zio2Effect[Any, Throwable](Runtime.default, identity, identity)
  private val adapter = new ZioHttpSpoonbill[Any]

  "ZIO ingress" should "abort queue overflow and release buffered input and ownership exactly once" in {
    val program = for {
      releases <- Ref.make(0)
      aborts   <- Ref.make(0)
      input     = Queue[Task, Bytes](2)
      result <- adapter
                  .runSocket(
                    _ => ZIO.unit,
                    handle => ZIO.foreachDiscard(1 to 3)(_ => handle(ChannelEvent.Read(WebSocketFrame.Text("éé")))),
                    ZStream.never,
                    input,
                    Reporter.PrintReporter,
                    release = () => releases.update(_ + 1),
                    onAbort = aborts.update(_ + 1),
                    maxMessageBytes = 4
                  )
                  .either
      count    <- releases.get
      aborted  <- aborts.get
      retained <- input.dequeueAll()
      ended    <- input.stream.pull()
    } yield (result.isLeft, count, aborted, retained.isEmpty, ended.isEmpty)
    val result = Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(program))
    result shouldBe Exit.succeed((true, 1, 1, true, true))
  }

  it should "reject oversized UTF-8 and initial fragments without admitting partial commands" in {
    for (frame <- List(WebSocketFrame.Text("ééa"), WebSocketFrame.Text("a", false))) {
      val program = for {
        input <- ZIO.succeed(Queue[Task, Bytes](2))
        result <- adapter
                    .runSocket(
                      _ => ZIO.unit,
                      handle => handle(ChannelEvent.Read(frame)),
                      ZStream.never,
                      input,
                      Reporter.PrintReporter,
                      maxMessageBytes = 4
                    )
                    .either
        retained <- input.dequeueAll()
      } yield (result.isLeft, retained.isEmpty)
      val result = Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(program))
      result shouldBe Exit.succeed((true, true))
    }
  }

  "Configured Netty decoder" should "reject an oversized frame using its header before payload allocation" in {
    val config = adapter.socketConfig(Some("json"), 4).decoderConfig
    config.withUTF8Validator shouldBe true
    val channel = new EmbeddedChannel(
      new WebSocket13FrameDecoder(
        config.expectMaskedFrames,
        config.allowExtensions,
        config.maxFramePayloadLength
      )
    )
    try {
      intercept[CorruptedWebSocketFrameException] {
        // No payload or masking key is needed to reject the declared length.
        channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x82.toByte, 0x85.toByte)))
      }
    } finally channel.finishAndReleaseAll()
  }

  it should "accept exactly the configured payload budget" in {
    val config  = adapter.socketConfig(None, 4).decoderConfig
    val channel = new EmbeddedChannel(new WebSocket13FrameDecoder(true, false, config.maxFramePayloadLength))
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x82.toByte, 0x84.toByte, 0, 0, 0, 0, 1, 2, 3, 4)))
      val frame = channel.readInbound[BinaryWebSocketFrame]()
      frame.content().readableBytes() shouldBe 4
      frame.release()
    } finally channel.finishAndReleaseAll()
  }
}
