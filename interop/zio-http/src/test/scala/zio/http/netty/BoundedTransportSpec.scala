package zio.http.netty

import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelInboundHandlerAdapter, ChannelHandlerContext}
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Exit, Promise, Queue, Runtime, Unsafe, ZIO}
import zio.http.{ChannelEvent, WebSocketChannelEvent}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

final class BoundedTransportSpec extends AnyFlatSpec with Matchers {
  private def run[A](effect: ZIO[Any, Throwable, A]): A = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }

  "Patched ZIO transport" should "bound the earliest queue before copying frames and settle overflow" in {
    val queue = run(Queue.unbounded[WebSocketChannelEvent])
    val handshake = run(Promise.make[Nothing, Boolean])
    val channel = new EmbeddedChannel()
    channel.pipeline().addLast(Names.HttpRequestDecoder, new ChannelInboundHandlerAdapter)
    channel.pipeline().addLast(new WebSocketAppHandler(new NettyRuntime(Runtime.default), queue, handshake, None))
    try {
      run(queue.takeAll)
      for (_ <- 1 to 16) channel.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new Array[Byte](1024))))
      run(queue.size) shouldBe 16
      channel.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new Array[Byte](1024))))
      channel.isOpen shouldBe false
      val remaining = run(queue.takeAll)
      remaining.size shouldBe 1
      remaining.head shouldBe a[ChannelEvent.ExceptionCaught]
      // A separate transport remains usable after the overloaded one closes.
    } finally channel.finishAndReleaseAll()
  }

  it should "load the derived artifact rather than shadowing upstream classes" in {
    val location = classOf[WebSocketAppHandler].getProtectionDomain.getCodeSource.getLocation.toString
    location should include ("spoonbill-zio-http-runtime")
    location should endWith (".jar")
  }

  "Raw frame deadline" should "expire partial input even when more bytes keep arriving" in {
    val aborted = new AtomicInteger(0)
    val channel = new EmbeddedChannel(new WebSocketFrameDeadline(100L, _ => { aborted.incrementAndGet(); () }))
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x82.toByte)))
      channel.advanceTimeBy(60L, TimeUnit.MILLISECONDS)
      channel.runScheduledPendingTasks()
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x84.toByte)))
      channel.advanceTimeBy(50L, TimeUnit.MILLISECONDS)
      channel.runScheduledPendingTasks()
      aborted.get() shouldBe 1
      channel.advanceTimeBy(100L, TimeUnit.MILLISECONDS)
      channel.runScheduledPendingTasks()
      aborted.get() shouldBe 1
    } finally channel.finishAndReleaseAll()
  }

  it should "reject fragmentation before any text bytes are decoded" in {
    val aborted = new AtomicInteger(0)
    val channel = new EmbeddedChannel(new WebSocketFrameDeadline(100L, _ => { aborted.incrementAndGet(); () }))
    try {
      // First byte alone identifies non-final text; no partial UTF-8 is copied.
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x01))) shouldBe false
      aborted.get() shouldBe 1
    } finally channel.finishAndReleaseAll()
  }

  it should "cancel a completed frame deadline and reset for the next frame" in {
    val aborted = new AtomicInteger(0)
    val channel = new EmbeddedChannel(new WebSocketFrameDeadline(100L, _ => { aborted.incrementAndGet(); () }))
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x82.toByte, 0x81.toByte, 0, 0, 0, 0, 1)))
      channel.advanceTimeBy(200L, TimeUnit.MILLISECONDS)
      channel.runScheduledPendingTasks()
      aborted.get() shouldBe 0
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x82.toByte)))
      channel.advanceTimeBy(110L, TimeUnit.MILLISECONDS)
      channel.runScheduledPendingTasks()
      aborted.get() shouldBe 1
    } finally channel.finishAndReleaseAll()
  }
}
