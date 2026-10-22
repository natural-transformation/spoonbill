/*
 * Copyright 2021 - 2023 Sporta Technologies PVT LTD & the ZIO HTTP contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.http.netty

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import zio.http.ChannelEvent.UserEvent
import zio.http.internal.ChannelState
import zio.http.{ChannelEvent, WebSocketChannelEvent, WebSocketFrame}

import io.netty.buffer.ByteBufUtil
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.ServerHandshakeStateEvent
import io.netty.handler.codec.http.websocketx.{WebSocketFrame => JWebSocketFrame, _}
import java.util.concurrent.TimeoutException

/**
 * A generic SocketApp handler that can be used on both - the client and the
 * server.
 */
private[zio] final class WebSocketAppHandler(
  zExec: NettyRuntime,
  queue: Queue[WebSocketChannelEvent],
  handshakeCompleted: Promise[Nothing, Boolean],
  onComplete: Option[Promise[Throwable, ChannelState]],
)(implicit trace: Trace)
    extends SimpleChannelInboundHandler[JWebSocketFrame] {

  def spoonbillBoundedIngressVersion: Int = 1

  implicit private val unsafeClass: Unsafe = Unsafe.unsafe

  // All producers run on this channel's event loop. Consumers can only reduce
  // the size, so checking before conversion and offering cannot exceed the cap.
  private var terminated = false

  override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
    if (onComplete.isEmpty) {
      // Install before the real frame decoder can receive any bytes. Replacing
      // the old HTTP decoder forwards its unread cumulation to the temporary
      // rejection decoder, so an eager pre-101 prefix cannot bypass the observer.
      ctx.pipeline().addBefore(
        Names.HttpRequestDecoder,
        "spoonbill-websocket-frame-deadline",
        new WebSocketFrameDeadline(30000L, error => terminate(ctx, error)),
      )
      ctx.pipeline().replace(Names.HttpRequestDecoder, Names.HttpRequestDecoder, new WebSocketUpgradeBoundary)
    }
  }

  private def dispatch(ctx: ChannelHandlerContext, event: ChannelEvent[JWebSocketFrame]): Unit = {
    // IMPORTANT: Offering to the queue must be run synchronously to avoid messages being added in the wrong order
    // The upstream queue is structurally unbounded, but every producer passes
    // this serialized admission gate before copying a frame into it.
    if (!terminated) {
      if (zExec.unsafeRunSync(queue.size) >= 16)
        terminate(ctx, new IllegalStateException("WebSocket transport ingress queue exhausted"))
      else {
        val _ = zExec.unsafeRunSync(queue.offer(event.map(frameFromNetty)))
      }
    }
  }

  private def terminate(ctx: ChannelHandlerContext, error: Throwable): Unit = {
    if (!terminated) {
      terminated = true
      handshakeCompleted.unsafe.succeed(false)
      val status = error match {
        case violation: CorruptedWebSocketFrameException => violation.closeStatus()
        case _: TimeoutException | _: IllegalArgumentException => WebSocketCloseStatus.POLICY_VIOLATION
        case _: IllegalStateException => WebSocketCloseStatus.TRY_AGAIN_LATER
        case _ => WebSocketCloseStatus.INTERNAL_SERVER_ERROR
      }
      // Use the normal outbound channel path so the protocol handler records
      // this Close before a receiver wakes and requests shutdown itself.
      // The adapter's forceCloseTimeout bounds a peer that will not read it.
      ctx.channel().writeAndFlush(new CloseWebSocketFrame(status))
      // Discard retained payloads, then leave one terminal signal to wake an
      // idle receiveAll consumer. Do not shut down the queue and strand it.
      zExec.unsafeRunSync(queue.takeAll *> queue.offer(ChannelEvent.exceptionCaught(error)))
      onComplete.foreach(_.unsafe.done(Exit.fail(error)))
      // Same protocol-aware close path used by WebSocketChannel.shutdown.
      ctx.close(): Unit
    }
  }

  override def channelRead0(ctx: ChannelHandlerContext, msg: JWebSocketFrame): Unit = {
    // Keep frame admission independently strict: direct/custom frame producers
    // must not deliver partial commands even without raw-byte observation.
    if (onComplete.isEmpty && (!msg.isFinalFragment || msg.isInstanceOf[ContinuationWebSocketFrame]))
      terminate(ctx, new IllegalArgumentException("Fragmented WebSocket input is unsupported"))
    else dispatch(ctx, ChannelEvent.read(msg))
  }

  override def channelRegistered(ctx: ChannelHandlerContext): Unit =
    dispatch(ctx, ChannelEvent.registered)

  override def channelUnregistered(ctx: ChannelHandlerContext): Unit = {
    if (!terminated) {
      terminated = true
      handshakeCompleted.unsafe.succeed(false)
      // Preserve every admitted response before ordinary peer closure. The
      // terminal gate reserves exactly one payload-free terminal beyond the
      // 16 admitted events; no producer can append after this point.
      zExec.unsafeRunSync(queue.offer(ChannelEvent.unregistered))
    }
    onComplete match {
      case Some(promise) => promise.unsafe.done(Exit.succeed(ChannelState.Invalid))
      case None          => ()
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    terminate(ctx, cause)
    onComplete match {
      case Some(promise) => promise.unsafe.done(Exit.fail(cause))
      case None          => ()
    }
  }

  override def userEventTriggered(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
    msg match {
      case _: WebSocketServerProtocolHandler.HandshakeComplete | ClientHandshakeStateEvent.HANDSHAKE_COMPLETE =>
        handshakeCompleted.unsafe.succeed(true)
        dispatch(ctx, ChannelEvent.userEventTriggered(UserEvent.HandshakeComplete))
      case ServerHandshakeStateEvent.HANDSHAKE_TIMEOUT | ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT          =>
        handshakeCompleted.unsafe.succeed(false)
        dispatch(ctx, ChannelEvent.userEventTriggered(UserEvent.HandshakeTimeout))
      case _ => super.userEventTriggered(ctx, msg)
    }
  }

  private def frameFromNetty(jFrame: JWebSocketFrame): WebSocketFrame =
    jFrame match {
      case _: PingWebSocketFrame         => WebSocketFrame.Ping
      case _: PongWebSocketFrame         => WebSocketFrame.Pong
      case m: BinaryWebSocketFrame       =>
        WebSocketFrame.Binary(Chunk.fromArray(ByteBufUtil.getBytes(m.content())), m.isFinalFragment)
      case m: TextWebSocketFrame         => WebSocketFrame.Text(m.text(), m.isFinalFragment)
      case m: CloseWebSocketFrame        => WebSocketFrame.Close(m.statusCode(), Option(m.reasonText()))
      case m: ContinuationWebSocketFrame =>
        WebSocketFrame.Continuation(Chunk.fromArray(ByteBufUtil.getBytes(m.content())), m.isFinalFragment)
      case _                             => null
    }
}
