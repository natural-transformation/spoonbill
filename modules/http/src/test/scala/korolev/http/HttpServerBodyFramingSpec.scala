package spoonbill.http

import java.net.{InetSocketAddress, Socket, SocketException}
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.web.{Request, Response}

final class HttpServerBodyFramingSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def write(socket: Socket, text: String): Unit = {
    socket.getOutputStream.write(text.getBytes(US_ASCII)); socket.getOutputStream.flush()
  }
  private def headers(socket: Socket): String = {
    val result = new StringBuilder
    while (!result.toString.endsWith("\r\n\r\n")) {
      val value = socket.getInputStream.read()
      if (value == -1) fail("Closed before response headers")
      result.append(value.toChar)
    }
    result.toString
  }
  private def closed(socket: Socket): Unit =
    try socket.getInputStream.read() shouldBe -1
    catch { case _: SocketException => () }
  private def serving(timeout: FiniteDuration = 2.seconds)(
    handle: Request[Stream[Future, Bytes]] => Future[Unit]
  )(test: (() => Socket) => Unit): Unit = {
    val server = await(HttpServer[Future, Bytes](new InetSocketAddress("127.0.0.1", 0),
      requestBodyTimeout = timeout) { request =>
      handle(request).map(_ => Response(Response.Status.Ok, Stream.empty[Future, Bytes], Nil, Some(0L)))
    })
    val sockets = scala.collection.mutable.ArrayBuffer.empty[Socket]
    try test(() => {
      val socket = new Socket()
      sockets += socket
      socket.connect(server.localAddress, 3000)
      socket.setSoTimeout(2000)
      socket
    }) finally { sockets.foreach(_.close()); await(server.stopServingRequests()) }
  }
  private val next = "GET /canary HTTP/1.1\r\n\r\n"

  "Standalone HTTP framing" should "return 400 and close ambiguous requests without dispatching their pipelined tail" in {
    val calls = new AtomicInteger()
    val fields = Seq(
      "Content-Length: 0\r\nContent-Length: 0", "Content-Length: 0\r\ncontent-length: 3",
      "Content-Length: 0, 0", "Content-Length: -1", "Content-Length: +1", "Content-Length: nope",
      "Content-Length: 9223372036854775808", "Content-Length:",
      "Content-Length: 0\r\nTransfer-Encoding: chunked", "Transfer-Encoding: chunked\r\nContent-Length: 0",
      "Transfer-Encoding: gzip, chunked", "Transfer-Encoding: chunked, chunked",
      "Transfer-Encoding: chunked\r\ntransfer-encoding: chunked", "Transfer-Encoding: identity"
    )
    serving() { request => calls.incrementAndGet(); request.body.foreach(_ => Future.unit) } { connect =>
      fields.foreach { value =>
        val socket = connect()
        write(socket, s"POST / HTTP/1.1\r\n$value\r\n\r\n0\r\n\r\n$next")
        headers(socket) should startWith("HTTP/1.1 400")
        closed(socket)
      }
      calls.get() shouldBe 0
      val good = connect()
      write(good, next)
      headers(good) should startWith("HTTP/1.1 200")
      calls.get() shouldBe 1
    }
  }

  it should "preserve chunked trailers and the next request on the same socket" in {
    val bodies = scala.collection.mutable.ArrayBuffer.empty[String]
    serving() { request => request.body.fold(Bytes.empty)(_ ++ _).map { body => bodies.synchronized { bodies += body.asUtf8String }; () } } { connect =>
      val socket = connect()
      write(socket, "POST / HTTP/1.1\r\nTransfer-Encoding: ChUnKeD\r\n\r\n4;foo=bar\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Checksum: yes\r\n\r\n" + next)
      headers(socket) should startWith("HTTP/1.1 200")
      headers(socket) should startWith("HTTP/1.1 200")
      bodies.synchronized(bodies.toVector) shouldBe Vector("Wikipedia", "")
    }
  }

  it should "close after responding to an ignored or canceled body without dispatching embedded requests" in {
    Seq(false, true).foreach { cancel =>
      val calls = new AtomicInteger()
      serving() { request =>
        calls.incrementAndGet()
        if (cancel) request.body.cancel() else Future.unit
      } { connect =>
        val socket = connect()
        write(socket, s"POST / HTTP/1.1\r\nContent-Length: ${next.length}\r\n\r\n$next$next")
        headers(socket) should startWith("HTTP/1.1 200")
        closed(socket)
        calls.get() shouldBe 1
      }
    }
  }

  it should "use an absolute body deadline across partial chunks and ordinary fixed-length bodies" in {
    Seq("Transfer-Encoding: chunked\r\n\r\n4\r\na", "Content-Length: 4\r\n\r\na").foreach { framing =>
      val entered = Promise[Unit]()
      serving(timeout = 800.millis) { request =>
        entered.trySuccess(())
        request.body.foreach(_ => Future.unit)
      } { connect =>
        val socket = connect()
        write(socket, "POST / HTTP/1.1\r\n" + framing)
        await(entered.future)
        Thread.sleep(600)
        write(socket, "b")
        socket.setSoTimeout(500) // Progress must not restart an 800ms deadline.
        closed(socket)
      }
    }
  }

  it should "cancel the body deadline at complete framing before delayed application work" in {
    val complete = Promise[Unit]()
    val release = Promise[Unit]()
    serving(timeout = 200.millis) { request =>
      request.body.foreach(_ => Future.unit).flatMap { _ => complete.trySuccess(()); release.future }
    } { connect =>
      val socket = connect()
      write(socket, "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\n\r\n")
      await(complete.future)
      Thread.sleep(300)
      release.success(())
      headers(socket) should startWith("HTTP/1.1 200")
    }
  }

  it should "keep late cancellation of an old body from abandoning the next request" in {
    val firstBody = Promise[Stream[Future, Bytes]]()
    val secondStarted = Promise[Unit]()
    val calls = new AtomicInteger()
    serving() { request =>
      calls.incrementAndGet() match {
        case 1 => firstBody.success(request.body); request.body.foreach(_ => Future.unit)
        case 2 => secondStarted.success(()); request.body.foreach(_ => Future.unit)
        case _ => request.body.foreach(_ => Future.unit)
      }
    } { connect =>
      val socket = connect()
      write(socket, "POST /first HTTP/1.1\r\nContent-Length: 1\r\n\r\na")
      headers(socket) should startWith("HTTP/1.1 200")
      write(socket, "POST /second HTTP/1.1\r\nContent-Length: 1\r\n\r\n")
      await(secondStarted.future)
      await(await(firstBody.future).cancel())
      write(socket, "b" + next)
      headers(socket) should startWith("HTTP/1.1 200")
      headers(socket) should startWith("HTTP/1.1 200")
      calls.get() shouldBe 3
    }
  }

  it should "keep cancellation sticky when an already pending body read finishes" in {
    val canceled = Promise[Unit]()
    val calls = new AtomicInteger()
    serving() { request =>
      calls.incrementAndGet()
      val reading = request.body.pull()
      request.body.cancel().flatMap { _ => canceled.success(()); reading.map(_ => ()) }
    } { connect =>
      val socket = connect()
      write(socket, "POST / HTTP/1.1\r\nContent-Length: 1\r\n\r\n")
      await(canceled.future)
      write(socket, "a" + next)
      headers(socket) should startWith("HTTP/1.1 200")
      closed(socket)
      calls.get() shouldBe 1
    }
  }
}
