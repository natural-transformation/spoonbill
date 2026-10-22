package zio.http.netty

import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelInboundHandlerAdapter, ChannelHandlerContext}
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.websocketx.{BinaryWebSocketFrame, WebSocket13FrameDecoder}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Exit, Promise, Queue, Runtime, Unsafe, ZIO}
import zio.http.{ChannelEvent, WebSocketChannelEvent, WebSocketFrame}
import zio.http.internal.ChannelState
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

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

  it should "preserve a full admitted client queue before the single normal-close terminal" in {
    val queue = run(Queue.unbounded[WebSocketChannelEvent])
    val handshake = run(Promise.make[Nothing, Boolean])
    val complete = run(Promise.make[Throwable, ChannelState])
    val channel = new EmbeddedChannel(new WebSocketAppHandler(new NettyRuntime(Runtime.default), queue, handshake, Some(complete)))
    try {
      run(queue.takeAll)
      for (value <- 1 to 16)
        channel.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(Array(value.toByte))))
      run(queue.size) shouldBe 16
      channel.close().syncUninterruptibly()
      // The one reserved terminal carries no payload and cannot displace data.
      run(queue.size) shouldBe 17
      val events = run(queue.takeAll)
      events.collect { case ChannelEvent.Read(WebSocketFrame.Binary(bytes)) => bytes.head.toInt }.toVector shouldBe
        (1 to 16).toVector
      events.last shouldBe ChannelEvent.Unregistered
      events.count(_ == ChannelEvent.Unregistered) shouldBe 1
      channel.close().syncUninterruptibly()
      run(queue.size) shouldBe 0
    } finally channel.finishAndReleaseAll()
  }

  "Raw frame observer installation" should "reject a decoder with an unknown already-consumed prefix" in {
    val aborted = new AtomicInteger(0)
    val channel = new EmbeddedChannel()
    channel.pipeline().addLast("decoder", new WebSocket13FrameDecoder(true, false, 64))
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x82.toByte, 0x90.toByte, 0, 0, 0, 0))) shouldBe false
      channel.pipeline().addBefore("decoder", "observer", new WebSocketFrameDeadline(100L, _ => { aborted.incrementAndGet(); () }))
      aborted.get() shouldBe 1
      // These are payload bytes, not an empty frame. No heuristic can safely
      // reconstruct the absent header; the late observer stays failed closed.
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x82.toByte, 0x80.toByte, 0, 0, 0, 0))) shouldBe false
      channel.advanceTimeBy(200L, TimeUnit.MILLISECONDS)
      channel.runScheduledPendingTasks()
      aborted.get() shouldBe 1
    } finally channel.finishAndReleaseAll()
  }

  "Raw frame deadline" should "expire partial input even when more bytes keep arriving" in {
    val aborted = new AtomicInteger(0)
    val channel = new EmbeddedChannel(new WebSocketFrameDeadline(100L, _ => { aborted.incrementAndGet(); () }, () => 0L))
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

  it should "reject overdue completion before the delayed timer callback runs" in {
    val clock = new AtomicLong(0L)
    val aborted = new AtomicInteger(0)
    val channel = new EmbeddedChannel(new WebSocketFrameDeadline(100L, _ => { aborted.incrementAndGet(); () }, () => clock.get()))
    channel.pipeline().addLast(new WebSocket13FrameDecoder(true, false, 64))
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x82.toByte))) shouldBe false
      clock.set(TimeUnit.MILLISECONDS.toNanos(60L))
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x81.toByte, 0, 0, 0, 0))) shouldBe false
      // Do not advance EmbeddedChannel's scheduler: the callback has not run.
      // Progress at 60ms must not have reset the original start timestamp.
      clock.set(TimeUnit.MILLISECONDS.toNanos(100L))
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](1))) shouldBe false
      aborted.get() shouldBe 1
      channel.advanceTimeBy(200L, TimeUnit.MILLISECONDS)
      channel.runScheduledPendingTasks()
      aborted.get() shouldBe 1
    } finally channel.finishAndReleaseAll()
  }

  it should "accept completion before the monotonic deadline and start the next frame afresh" in {
    val clock = new AtomicLong(0L)
    val aborted = new AtomicInteger(0)
    val channel = new EmbeddedChannel(new WebSocketFrameDeadline(100L, _ => { aborted.incrementAndGet(); () }, () => clock.get()))
    channel.pipeline().addLast(new WebSocket13FrameDecoder(true, false, 64))
    try {
      clock.set(TimeUnit.MILLISECONDS.toNanos(99L))
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x82.toByte, 0x81.toByte, 0, 0, 0, 0, 1))) shouldBe true
      channel.readInbound[BinaryWebSocketFrame]().release()
      clock.set(TimeUnit.MILLISECONDS.toNanos(1000L))
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x82.toByte))) shouldBe false
      clock.set(TimeUnit.MILLISECONDS.toNanos(1099L))
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x81.toByte, 0, 0, 0, 0, 2))) shouldBe true
      channel.readInbound[BinaryWebSocketFrame]().release()
      aborted.get() shouldBe 0
    } finally channel.finishAndReleaseAll()
  }

  it should "reject fragmentation before any text bytes are decoded" in {
    val aborted = new AtomicInteger(0)
    val channel = new EmbeddedChannel(new WebSocketFrameDeadline(100L, _ => { aborted.incrementAndGet(); () }, () => 0L))
    try {
      // First byte alone identifies non-final text; no partial UTF-8 is copied.
      channel.writeInbound(Unpooled.wrappedBuffer(Array[Byte](0x01))) shouldBe false
      aborted.get() shouldBe 1
    } finally channel.finishAndReleaseAll()
  }

  it should "cancel a completed frame deadline and reset for the next frame" in {
    val aborted = new AtomicInteger(0)
    val channel = new EmbeddedChannel(new WebSocketFrameDeadline(100L, _ => { aborted.incrementAndGet(); () }, () => 0L))
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
