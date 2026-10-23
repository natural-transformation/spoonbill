package zio.http.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{ChannelHandlerContext, ChannelInitializer, SimpleChannelInboundHandler}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.{FullHttpRequest, HttpDecoderConfig, HttpObjectAggregator, HttpRequestDecoder, HttpResponseEncoder}
import io.netty.handler.codec.http.websocketx.{WebSocketDecoderConfig, WebSocketServerProtocolConfig, WebSocketServerProtocolHandler}
import java.net.{InetSocketAddress, Socket, SocketException}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Promise as ScalaPromise}
import scala.concurrent.duration.*
import zio.{Promise, Queue, Runtime, Unsafe, ZIO}
import zio.http.{ChannelEvent, WebSocketChannelEvent}

/** Real HTTP decoding, actual handshaker replacement, and a loopback peer.
  * Lenient HTTP parsing is a supported Netty setting; strict default parsing
  * can reject these eager bytes earlier, before the upgrade handler is built.
  */
final class WebSocketUpgradeSocketSpec extends AnyFlatSpec with Matchers {
  private def run[A](effect: ZIO[Any, Throwable, A]): A = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }

  "HTTP-to-WebSocket handoff" should "reject a partial pre-101 frame retained by the old HTTP decoder" in {
    val queue = run(Queue.unbounded[WebSocketChannelEvent])
    val handshake = run(Promise.make[Nothing, Boolean])
    val requestReady = ScalaPromise[(ChannelHandlerContext, FullHttpRequest)]()
    val prefixRetained = ScalaPromise[Int]()
    val boss = new NioEventLoopGroup(1)
    val worker = new NioEventLoopGroup(1)
    val server = new ServerBootstrap().group(boss, worker).channel(classOf[NioServerSocketChannel])
      .childHandler(new ChannelInitializer[SocketChannel] {
        override def initChannel(channel: SocketChannel): Unit = {
          channel.pipeline().addLast(Names.HttpRequestDecoder,
            new HttpRequestDecoder(new HttpDecoderConfig().setStrictLineParsing(false)) {
              override def channelRead(ctx: ChannelHandlerContext, message: Any): Unit = {
                super.channelRead(ctx, message)
                val retained = actualReadableBytes()
                if (retained > 0) prefixRetained.trySuccess(retained)
                ()
              }
            })
          channel.pipeline().addLast("http-encoder", new HttpResponseEncoder)
          channel.pipeline().addLast("http-aggregator", new HttpObjectAggregator(8192))
          channel.pipeline().addLast("request", new SimpleChannelInboundHandler[FullHttpRequest] {
            override def channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest): Unit = {
              val retained = request.retain()
              if (!requestReady.trySuccess(ctx -> retained)) retained.release()
              ()
            }
          })
        }
      }).bind("127.0.0.1", 0).syncUninterruptibly().channel()
    val socket = new Socket("127.0.0.1", server.localAddress().asInstanceOf[InetSocketAddress].getPort)
    var retainedRequest: FullHttpRequest = null
    try {
      socket.setSoTimeout(3000)
      val headers = ("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
        "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: json\r\n\r\n")
        .getBytes(StandardCharsets.US_ASCII)
      val prefix = Array[Byte](0x82.toByte, 0x90.toByte, 0, 0, 0, 0)
      socket.getOutputStream.write(headers ++ prefix)
      socket.getOutputStream.flush()
      val (context, request) = Await.result(requestReady.future, 3.seconds)
      retainedRequest = request
      Await.result(prefixRetained.future, 3.seconds) shouldBe prefix.length
      val connection = context.channel()
      // Match upstream ordering: add protocol/app handlers, then dispatch the
      // retained HTTP request on the event loop for the actual handshaker.
      connection.eventLoop().submit(new Runnable {
        def run(): Unit = {
          val config = WebSocketServerProtocolConfig.newBuilder().websocketPath("/").subprotocols("json")
            .forceCloseTimeoutMillis(1000L)
            .decoderConfig(WebSocketDecoderConfig.newBuilder().maxFramePayloadLength(64).closeOnProtocolViolation(false).build())
            .build()
          connection.pipeline().addLast(new WebSocketServerProtocolHandler(config))
          connection.pipeline().addLast(Names.WebSocketHandler,
            new WebSocketAppHandler(new NettyRuntime(Runtime.default), queue, handshake, None))
          if (connection.isActive) context.fireChannelRead(request)
          else request.release()
          ()
        }
      }).syncUninterruptibly()
      val first = try socket.getInputStream.read() catch {
        case error: SocketException if Option(error.getMessage).exists(_.toLowerCase.contains("connection reset")) => -2
      }
      assert(first == -1 || first == -2, "Eager input must not receive HTTP 101 or a WebSocket payload")
      connection.closeFuture().await(3000L) shouldBe true // before client cleanup
      val events = run(queue.takeAll)
      events.exists { case ChannelEvent.Read(_) => true; case _ => false } shouldBe false
      events.count(_ == ChannelEvent.Unregistered) shouldBe 1
    } finally {
      socket.close()
      server.close().syncUninterruptibly()
      boss.shutdownGracefully(0L, 2L, TimeUnit.SECONDS).syncUninterruptibly()
      worker.shutdownGracefully(0L, 2L, TimeUnit.SECONDS).syncUninterruptibly()
      if (retainedRequest != null && retainedRequest.refCnt() > 0) retainedRequest.release()
    }
  }
}
