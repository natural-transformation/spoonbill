package external

import java.net.{InetSocketAddress, Socket, SocketAddress, SocketException}
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.http.HttpServer
import spoonbill.http.protocol.WebSocketProtocol
import spoonbill.web.{Headers, Request, Response}

/** Deliberately outside spoonbill: only public server and protocol APIs compile here. */
final class HttpServerPublicUpgradeSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private val handshakeKey = "dGhlIHNhbXBsZSBub25jZQ=="
  private val handshake = "GET /echo HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
    s"Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: $handshakeKey\r\n"
  private val protocol = new WebSocketProtocol[Bytes]()
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def write(socket: Socket, bytes: Array[Byte]): Unit = {
    socket.getOutputStream.write(bytes); socket.getOutputStream.flush()
  }
  private def headers(socket: Socket): String = {
    val text = new StringBuilder
    while (!text.toString.endsWith("\r\n\r\n")) {
      val next = socket.getInputStream.read()
      if (next < 0) fail("Closed before response headers")
      text.append(next.toChar)
    }
    text.toString
  }
  private def socket[A](address: SocketAddress)(run: Socket => A): A = {
    val value = new Socket()
    try {
      value.connect(address, 3000)
      value.setSoTimeout(3000)
      run(value)
    } finally value.close()
  }
  private def serving(handle: Request[Stream[Future, Bytes]] => Future[Response[Stream[Future, Bytes]]])(
    test: SocketAddress => Unit
  ): Unit = {
    val server = await(HttpServer[Future, Bytes](new InetSocketAddress("127.0.0.1", 0))(handle))
    try test(server.localAddress)
    finally await(server.stopServingRequests())
  }
  private def echo(request: Request[Stream[Future, Bytes]]): Future[Response[Stream[Future, Bytes]]] =
    protocol.upgrade[Future](protocol.findIntention(request).get) { frames =>
      Future.successful(Response(Response.Status.Ok, frames.body, Nil, None))
    }.apply(request)
  private def exchange(address: SocketAddress, fields: String = ""): Unit = socket(address) { peer =>
    write(peer, (handshake + fields + "\r\n").getBytes(US_ASCII))
    headers(peer) should startWith("HTTP/1.1 101")
    // A compliant client sends a masked frame only after receiving HTTP 101.
    write(peer, Array[Byte](0x82.toByte, 0x81.toByte, 0, 0, 0, 0, 42))
    peer.getInputStream.read() shouldBe 0x82
    peer.getInputStream.read() shouldBe 1
    peer.getInputStream.read() shouldBe 42
  }

  "Public HttpServer and WebSocketProtocol composition" should "preserve frame input after an accepted handshake" in {
    serving(echo) { address =>
      exchange(address)
      exchange(address, "Content-Length: 0\r\n")
    }
  }

  it should "keep ordinary unframed bodies empty and cancellation harmless until upgrade is explicitly accepted" in {
    serving { request =>
      request.body.pull().flatMap { empty =>
        empty shouldBe None
        request.body.cancel().flatMap(_ => echo(request))
      }
    }(exchange(_))
  }

  it should "leave ordinary HTTP pipelining intact even when a caller supplies upgrade headers" in {
    val calls = new AtomicInteger()
    serving { request =>
      calls.incrementAndGet()
      request.body.fold(Bytes.empty)(_ ++ _).flatMap { bytes =>
        bytes.length shouldBe 0L
        request.body.cancel().map(_ => Response(Response.Status.Ok, Stream.empty[Future, Bytes], Nil, Some(0L)))
      }
    } { address =>
      for (first <- Seq(handshake + "\r\n", s"GET / HTTP/1.1\r\nSec-WebSocket-Key: $handshakeKey\r\n\r\n")) {
        socket(address) { peer =>
          write(peer, (first + "GET /next HTTP/1.1\r\n\r\n").getBytes(US_ASCII))
          headers(peer) should startWith("HTTP/1.1 200")
          headers(peer) should startWith("HTTP/1.1 200")
        }
      }
      calls.get() shouldBe 4
    }
  }

  it should "reject malformed handshakes and HTTP payload framing before calling the frame handler" in {
    val accepted = new AtomicInteger()
    serving { request =>
      val intention = WebSocketProtocol.Intention(request.header(Headers.SecWebSocketKey).getOrElse(handshakeKey).trim)
      protocol.upgrade[Future](intention) { frames =>
        accepted.incrementAndGet()
        Future.successful(Response(Response.Status.Ok, frames.body, Nil, None))
      }.apply(request)
    } { address =>
      val invalid = Seq(
        handshake.replace("GET /", "POST /") + "\r\n",
        handshake.replace("Connection: Upgrade\r\n", "") + "\r\n",
        handshake.replace("Upgrade: websocket\r\n", "") + "\r\n",
        handshake.replace("Version: 13", "Version: 12") + "\r\n",
        handshake.replace(handshakeKey, "invalid") + "\r\n",
        handshake + s"Sec-WebSocket-Key: $handshakeKey\r\n\r\n",
        handshake + "Content-Length: 1\r\n\r\nx",
        handshake + "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
      )
      invalid.foreach { request => socket(address) { peer =>
        write(peer, request.getBytes(US_ASCII))
        headers(peer) should startWith("HTTP/1.1 400")
      } }
      accepted.get() shouldBe 0
      exchange(address)
      accepted.get() shouldBe 1
    }
  }

  it should "seal an unused upgrade capability when its HTTP response is returned" in {
    val captured = Promise[Request[Stream[Future, Bytes]]]()
    serving { request =>
      captured.trySuccess(request)
      Future.successful(Response(Response.Status.Ok, Stream.empty[Future, Bytes], Nil, Some(0L)))
    } { address => socket(address) { peer =>
      write(peer, (handshake + "\r\n").getBytes(US_ASCII))
      headers(peer) should startWith("HTTP/1.1 200")
      intercept[IllegalStateException](await(echo(await(captured.future))))
      write(peer, "GET /next HTTP/1.1\r\n\r\n".getBytes(US_ASCII))
      headers(peer) should startWith("HTTP/1.1 200")
    } }
  }

  it should "permit only one upgrade claim during a handler evaluation" in {
    serving { request =>
      echo(request).flatMap { first =>
        echo(request).map(_ => fail("A second upgrade claim succeeded")).recover {
          case _: IllegalStateException => first
        }
      }
    }(exchange(_))
  }

  it should "close and cancel a claimed upgrade whose handler replaces HTTP 101 with an ordinary response" in {
    val canceled = Promise[Unit]()
    serving { request =>
      protocol.upgrade[Future](protocol.findIntention(request).get) { _ =>
        val output = new Stream[Future, WebSocketProtocol.Frame.Merged[Bytes]] {
          def pull(): Future[Option[WebSocketProtocol.Frame.Merged[Bytes]]] = Future.never
          def cancel(): Future[Unit] = Future.successful { canceled.trySuccess(()); () }
        }
        Future.successful(Response(Response.Status.Ok, output, Nil, None))
      }.apply(request).map(_.copy(status = Response.Status.Ok))
    } { address => socket(address) { peer =>
      write(peer, (handshake + "\r\n").getBytes(US_ASCII))
      try peer.getInputStream.read() shouldBe -1
      catch { case _: SocketException => () }
      await(canceled.future)
    } }
  }
}
