package zio.http.netty

import java.util.concurrent.{ScheduledFuture, TimeUnit, TimeoutException}
import io.netty.buffer.ByteBuf
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder
import io.netty.util.ReferenceCountUtil

/** Reads wire framing without retaining payloads or changing reader indexes.
  * It is installed after TLS, before the decoder, on the server upgrade path.
  * Fragmentation is explicitly unsupported: reject at the first header, before
  * UTF-8 conversion or application delivery, including split code points.
  */
private[zio] final class WebSocketFrameDeadline(
  timeoutMillis: Long,
  abort: Throwable => Unit,
  nanoTime: () => Long = () => System.nanoTime()
)
    extends ChannelInboundHandlerAdapter {
  require(timeoutMillis > 0, "frame assembly timeout must be positive")
  private val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
  // One event-loop-owned storage cell. The pure scan is inlined at its boundary
  // so immutable input/result records need not escape each network read. No
  // parser decision is duplicated here, and no counter is narrowed or packed.
  private final class Storage {
    var headerBytes = 0
    var headerLength = 2
    var lengthTag = 0
    var payloadRemaining = 0L
    var readingPayload = false
    var deadline = WebSocketFrameTimer.Phase.Idle
    var startedAt = 0L
    inline def scanner: WebSocketFrameScan.State =
      WebSocketFrameScan.State(headerBytes, headerLength, lengthTag, payloadRemaining, readingPayload)
    inline def install(next: WebSocketFrameScan.State): Unit = {
      headerBytes = next.headerBytes
      headerLength = next.headerLength
      lengthTag = next.lengthTag
      payloadRemaining = next.payloadRemaining
      readingPayload = next.readingPayload
    }
  }
  private val state = new Storage
  private var timer: ScheduledFuture[?] = null
  private def failed: Boolean = state.deadline == WebSocketFrameTimer.Phase.Failed

  override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
    // An existing decoder may already have consumed a header into its private
    // state. Never guess alignment by interpreting later payload as a header.
    if (ctx.pipeline().get(classOf[WebSocketFrameDecoder]) != null)
      reject(new IllegalStateException("WebSocket deadline observer installed after frame decoding began"))
    else begin(ctx)
  }

  private def cancelTimer(): Unit = {
    if (timer != null) timer.cancel(false)
    timer = null
  }

  private def reject(error: Throwable): Unit = {
    if (!failed) {
      state.deadline = WebSocketFrameTimer.Phase.Failed
      cancelTimer()
      abort(error)
    }
  }

  private def begin(ctx: ChannelHandlerContext): Unit = {
    if (timer == null) {
      state.startedAt = nanoTime()
      state.deadline = WebSocketFrameTimer.Phase.Armed
      timer = ctx.executor().schedule(new Runnable {
        def run(): Unit = reject(new TimeoutException("WebSocket frame assembly timed out"))
      }, timeoutMillis, TimeUnit.MILLISECONDS)
    }
  }

  private def complete(): Unit = {
    // A busy event loop can process this read before an overdue timer task.
    // Completion must enforce the same absolute deadline independently.
    val next = WebSocketFrameTimer.complete(state.deadline, state.startedAt, nanoTime(), timeoutNanos)
    if (next == WebSocketFrameTimer.Phase.Failed)
      reject(new TimeoutException("WebSocket frame assembly timed out"))
    else {
      cancelTimer()
      state.install(WebSocketFrameScan.initial)
      state.deadline = next
    }
  }

  private def scan(ctx: ChannelHandlerContext, bytes: ByteBuf): Unit = {
    var index = bytes.readerIndex()
    val end = bytes.writerIndex()
    while (index < end && !failed) {
      begin(ctx)
      val result = WebSocketFrameScan.scan(state.scanner, bytes, index)
      state.install(result.state)
      index = result.index
      result.boundary match {
        case WebSocketFrameScan.Boundary.Complete => complete()
        case WebSocketFrameScan.Boundary.Fragmented => reject(new IllegalArgumentException("Fragmented WebSocket input is unsupported"))
        case WebSocketFrameScan.Boundary.InvalidLength => reject(new IllegalArgumentException("Invalid WebSocket frame length"))
        case WebSocketFrameScan.Boundary.More => ()
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
    state.deadline = WebSocketFrameTimer.Phase.Failed
    cancelTimer()
    ctx.fireChannelInactive(): Unit
  }

  override def handlerRemoved(ctx: ChannelHandlerContext): Unit = cancelTimer()
}
