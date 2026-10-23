package zio.http.netty

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpRequestDecoder
import java.util.List

/** Accepted upgrades temporarily replace the HTTP decoder with this empty
  * decoder. Any unread prefix forwarded by removal, or bytes arriving before
  * Netty installs its WebSocket decoder, fail closed instead of entering an
  * observer with an unknown frame boundary. Clients must wait for HTTP 101.
  * Netty's handshaker replaces this decoder before writing that response.
  */
private[zio] final class WebSocketUpgradeBoundary extends HttpRequestDecoder {
  override protected def decode(ctx: ChannelHandlerContext, bytes: ByteBuf, out: List[AnyRef]): Unit = {
    if (bytes.isReadable) {
      bytes.skipBytes(bytes.readableBytes())
      // This context precedes protocol handlers: no WebSocket Close is emitted
      // before the HTTP upgrade has completed. Unregistered settles the app.
      ctx.close(): Unit
    }
  }
}
