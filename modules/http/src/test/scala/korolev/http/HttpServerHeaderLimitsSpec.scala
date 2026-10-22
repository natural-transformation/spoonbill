package spoonbill.http

import java.net.{InetSocketAddress, Socket, SocketException}
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.web.{Request, Response}

final class HttpServerHeaderLimitsSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private val completeRequest = "GET / HTTP/1.1\r\nContent-Length: 0\r\n\r\n"
  private def write(socket: Socket, value: String): Unit = {
    socket.getOutputStream.write(value.getBytes(US_ASCII))
    socket.getOutputStream.flush()
  }
  private def closed(socket: Socket): Unit =
    try socket.getInputStream.read() shouldBe -1
    catch { case _: SocketException => () } // Aborting unread input may reset TCP.

  private def response(socket: Socket): String = {
    val text = new StringBuilder
    while (!text.toString.endsWith("\r\n\r\n")) {
      val next = socket.getInputStream.read()
      if (next < 0) fail("Connection closed before response")
      text.append(next.toChar)
    }
    text.toString
  }

  private def serving(timeout: FiniteDuration = 800.millis, limit: Int = 128)(
    handle: Request[Stream[Future, Bytes]] => Future[Unit]
  )(test: (() => Socket) => Unit): Unit = {
    val server = await(HttpServer[Future, Bytes](new InetSocketAddress("127.0.0.1", 0),
      maxRequestHeaderBytes = limit, requestHeaderTimeout = timeout) { request =>
      handle(request).map(_ => Response(Response.Status.Ok, Stream.empty[Future, Bytes], Nil, contentLength = Some(0L)))
    })
    val sockets = scala.collection.mutable.ArrayBuffer.empty[Socket]
    try test(() => {
      val socket = new Socket()
      sockets += socket
      socket.connect(server.localAddress, 3000)
      socket.setSoTimeout(2500)
      socket
    })
    finally {
      sockets.foreach(_.close())
      await(server.stopServingRequests())
    }
  }

  "HTTP request headers" should "close an oversized header before dispatch and keep accepting healthy clients" in {
    val calls = new AtomicInteger()
    serving(limit = completeRequest.length) { _ => Future.successful { calls.incrementAndGet(); () } } { connect =>
      val bad = connect()
      write(bad, "GET / HTTP/1.1\r\nX-Large: " + "a" * 1024)
      closed(bad)
      calls.get() shouldBe 0
      val good = connect()
      write(good, completeRequest)
      response(good) should startWith("HTTP/1.1 200")
      calls.get() shouldBe 1
    }
  }

  it should "expire incomplete and entirely idle headers without another byte" in {
    val calls = new AtomicInteger()
    serving(timeout = 200.millis) { _ => Future.successful { calls.incrementAndGet(); () } } { connect =>
      val incomplete = connect()
      write(incomplete, "GET / HTTP/1.1\r\nHost:")
      closed(incomplete)
      val idle = connect()
      closed(idle)
      calls.get() shouldBe 0
    }
  }

  it should "close complete malformed headers promptly without dispatch and recover for healthy requests" in {
    val calls = new AtomicInteger()
    val malformed = Seq(
      "GET / HTTP/1.1\r\nBad\r\n\r\n",
      "GET / HTTP/1.1\r\nBad\r\nHost: valid\r\n\r\n",
      "GET / HTTP/1.1\r\nBad\r\n\r\nBody: not a header\r\n",
      "GET / HTTP/1.1\r\nX: value\rY: next\r\n\r\n",
      "GET  HTTP/1.1\r\n\r\n"
    )
    // Longer than the socket read timeout: closure must come from parsing, not
    // the assembly timer masking a stuck or accepted malformed request.
    serving(timeout = 10.seconds) { _ => Future.successful { calls.incrementAndGet(); () } } { connect =>
      malformed.foreach { input =>
        val socket = connect()
        write(socket, input)
        closed(socket)
      }
      calls.get() shouldBe 0
      val good = connect()
      write(good, completeRequest)
      response(good) should startWith("HTTP/1.1 200")
      calls.get() shouldBe 1
    }
  }

  it should "reject a malformed keepalive request without dispatching it after a healthy request" in {
    val calls = new AtomicInteger()
    serving(timeout = 10.seconds) { _ => Future.successful { calls.incrementAndGet(); () } } { connect =>
      val socket = connect()
      write(socket, completeRequest)
      response(socket) should startWith("HTTP/1.1 200")
      write(socket, "GET / HTTP/1.1\r\nMissing-Colon\r\n\r\n")
      closed(socket)
      calls.get() shouldBe 1
    }
  }

  it should "use an absolute deadline that header progress cannot reset" in {
    serving()(_ => Future.successful(())) { connect =>
      val socket = connect()
      write(socket, "GET / HTTP/1.1\r\nX-Slow:")
      Thread.sleep(600)
      write(socket, " a")
      socket.setSoTimeout(500) // A reset deadline would leave this read waiting another 800ms.
      closed(socket)
    }
  }

  it should "start a new deadline for each keepalive request" in {
    serving(timeout = 300.millis)(_ => Future.successful(())) { connect =>
      val socket = connect()
      write(socket, completeRequest)
      response(socket) should startWith("HTTP/1.1 200")
      write(socket, completeRequest)
      response(socket) should startWith("HTTP/1.1 200")
      write(socket, "GET / HTTP/1.1\r\n")
      closed(socket)
    }
  }

  it should "stop the header timer while reading the body and waiting for the handler" in {
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    serving(timeout = 200.millis) { request =>
      entered.trySuccess(())
      request.body.fold(Bytes.empty)(_ ++ _).flatMap { bytes =>
        bytes.asUtf8String shouldBe "body"
        release.future
      }
    } { connect =>
      val socket = connect()
      write(socket, "POST / HTTP/1.1\r\nContent-Length: 4\r\n\r\n")
      await(entered.future)
      Thread.sleep(300)
      write(socket, "body")
      Thread.sleep(300)
      release.success(())
      response(socket) should startWith("HTTP/1.1 200")
    }
  }

  it should "reject elapsed completion even before its scheduled timer has run" in {
    val time = new AtomicLong(0L)
    val deadline = new RequestHeaderDeadline[Future](1.minute, () => Future.unit, () => time.get())
    try {
      deadline.start()
      time.set(1.minute.toNanos)
      deadline.complete() shouldBe false
      deadline.start()
      deadline.complete() shouldBe false
    } finally deadline.close()
  }
}
