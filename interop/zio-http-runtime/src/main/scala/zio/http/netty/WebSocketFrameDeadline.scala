package zio.http.netty

import java.util.concurrent.{ScheduledFuture, TimeUnit, TimeoutException}
import io.netty.buffer.ByteBuf
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.util.ReferenceCountUtil

/** Reads wire framing without retaining payloads or changing reader indexes.
  * It is installed after TLS, before the decoder, on the server upgrade path.
  * Fragmentation is explicitly unsupported: reject at the first header, before
  * UTF-8 conversion or application delivery, including split code points.
  */
private[zio] final class WebSocketFrameDeadline(timeoutMillis: Long, abort: Throwable => Unit)
    extends ChannelInboundHandlerAdapter {
  require(timeoutMillis > 0, "frame assembly timeout must be positive")
  private val header = new Array[Byte](14)
  private var headerBytes = 0
  private var headerLength = 2
  private var payloadRemaining = 0L
  private var readingPayload = false
  private var failed = false
  private var timer: ScheduledFuture[?] = null

  // Also bound eager bytes already held by the HTTP decoder when it is
  // replaced during upgrade, even if the peer never sends another byte.
  override def handlerAdded(ctx: ChannelHandlerContext): Unit = begin(ctx)

  private def cancelTimer(): Unit = {
    if (timer != null) timer.cancel(false)
    timer = null
  }

  private def reject(error: Throwable): Unit = {
    if (!failed) {
      failed = true
      cancelTimer()
      abort(error)
    }
  }

  private def begin(ctx: ChannelHandlerContext): Unit = {
    if (timer == null) timer = ctx.executor().schedule(new Runnable {
      def run(): Unit = reject(new TimeoutException("WebSocket frame assembly timed out"))
    }, timeoutMillis, TimeUnit.MILLISECONDS)
  }

  private def complete(): Unit = {
    cancelTimer()
    headerBytes = 0
    headerLength = 2
    readingPayload = false
  }

  private def scan(ctx: ChannelHandlerContext, bytes: ByteBuf): Unit = {
    var index = bytes.readerIndex()
    val end = bytes.writerIndex()
    while (index < end && !failed) {
      begin(ctx)
      if (readingPayload) {
        val count = math.min(payloadRemaining, (end - index).toLong).toInt
        payloadRemaining -= count
        index += count
        if (payloadRemaining == 0) complete()
      } else {
        header(headerBytes) = bytes.getByte(index)
        headerBytes += 1
        index += 1
        if (headerBytes == 1) {
          val first = header(0) & 0xff
          if ((first & 0x80) == 0 || (first & 0x0f) == 0)
            reject(new IllegalArgumentException("Fragmented WebSocket input is unsupported"))
        }
        if (headerBytes == 2) {
          val length = header(1) & 0x7f
          headerLength = 2 + (if (length == 126) 2 else if (length == 127) 8 else 0) +
            (if ((header(1) & 0x80) != 0) 4 else 0)
        }
        if (!failed && headerBytes == headerLength) {
          val length = header(1) & 0x7f
          if (length < 126) payloadRemaining = length.toLong
          else {
            val lengthBytes = if (length == 126) 2 else 8
            payloadRemaining = 0L
            var i = 0
            while (i < lengthBytes) {
              payloadRemaining = (payloadRemaining << 8) | (header(i + 2) & 0xff).toLong
              i += 1
            }
          }
          if (payloadRemaining < 0) reject(new IllegalArgumentException("Invalid WebSocket frame length"))
          else if (payloadRemaining == 0) complete()
          else readingPayload = true
        }
      }
    }
  }

  override def channelRead(ctx: ChannelHandlerContext, message: Any): Unit = {
    message match {
      case bytes: ByteBuf if !failed => scan(ctx, bytes)
      case _ => ()
    }
    if (failed) ReferenceCountUtil.release(message): Unit
    else ctx.fireChannelRead(message): Unit
  }

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    failed = true
    cancelTimer()
    ctx.fireChannelInactive(): Unit
  }

  override def handlerRemoved(ctx: ChannelHandlerContext): Unit = cancelTimer()
}
