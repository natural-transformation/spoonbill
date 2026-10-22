package spoonbill.http

import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.{Executors, TimeoutException}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, blocking}
import scala.concurrent.duration.*
import scala.util.{Try, Using}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.Reporter.PrintReporter.Implicit
import spoonbill.web.{PathAndQuery, Request, Response}

final class HttpClientBodylessResponseSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private val healthy = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nsafe"

  private class Fixture(deadline: FiniteDuration = 2.seconds) extends AutoCloseable {
    val listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    listener.setSoTimeout(5000)
    private val sockets = new java.util.concurrent.ConcurrentLinkedQueue[Socket]()
    private val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    private val address = new InetSocketAddress("127.0.0.1", listener.getLocalPort)
    private val client = await(HttpClient.create[Future, Bytes](
      group = group, maxConnectionsPerAddress = 1, maxTotalConnections = 1,
      responseHeaderTimeout = deadline))

    def accept(): Socket = {
      val socket = listener.accept()
      sockets.add(socket)
      socket.setSoTimeout(5000)
      socket
    }
    def request(method: Request.Method = Request.Method.Get): Future[Response[Stream[Future, Bytes]]] =
      client.http(address, Request(method, PathAndQuery.Root, Nil, Some(0L), Stream.empty))

    def close(): Unit = {
      Try(listener.close())
      val iterator = sockets.iterator()
      while (iterator.hasNext) Try(iterator.next().close())
      Try(group.shutdownNow())
      ()
    }
  }

  private def headers(socket: Socket): String = {
    val read = new StringBuilder
    while (!read.toString.endsWith("\r\n\r\n") && read.length < 16384) {
      val next = socket.getInputStream.read()
      if (next < 0) fail("Client closed before request headers")
      read.append(next.toChar)
    }
    read.toString should endWith("\r\n\r\n")
    read.toString
  }
  private def send(socket: Socket, value: String): Unit = {
    socket.getOutputStream.write(value.getBytes(US_ASCII))
    socket.getOutputStream.flush()
  }

  for {
    (method, status) <- Seq(
      Request.Method.Get -> "204 No Content",
      Request.Method.Get -> "304 Not Modified",
      Request.Method.Head -> "200 OK")
    field <- Seq("", "Content-Length: 42\r\n", "Transfer-Encoding: chunked\r\n")
  } {
    s"Bodyless response ($method, $status, ${field.trim})" should "finish and reuse the same persistent connection" in {
      Using.resource(new Fixture()) { fixture =>
        val server = Future(blocking {
          val socket = fixture.accept()
          headers(socket) should startWith(method.value + " ")
          send(socket, s"HTTP/1.1 $status\r\n$field\r\n")
          // No EOF or body bytes: a second request on this exact socket proves
          // both body completion and return of the one available pool slot.
          headers(socket) should startWith("GET ")
          send(socket, healthy)
        })
        val response = await(fixture.request(method))
        response.status.code shouldBe status.take(3).toInt
        response.contentLength shouldBe (if (field.startsWith("Content-Length")) Some(42L) else None)
        await(response.body.pull()) shouldBe None
        await(await(fixture.request()).body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
        await(server)
      }
    }
  }

  "Informational responses" should "wait for the final response and then reuse the persistent connection" in {
    Using.resource(new Fixture()) { fixture =>
      val server = Future(blocking {
        val socket = fixture.accept()
        headers(socket)
        send(socket, "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 103 Early Hints\r\n\r\n" + healthy)
        headers(socket)
        send(socket, healthy)
      })
      for (_ <- 1 to 2) {
        val response = await(fixture.request())
        response.status.code shouldBe 200
        await(response.body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
      }
      await(server)
    }
  }

  it should "retain the header deadline while a peer withholds the final response" in {
    Using.resource(new Fixture(250.millis)) { fixture =>
      val server = Future(blocking {
        val socket = fixture.accept()
        headers(socket)
        send(socket, "HTTP/1.1 103 Early Hints\r\n\r\n")
        socket.getInputStream.read() shouldBe -1
        socket.close()
        val recovered = fixture.accept()
        headers(recovered)
        send(recovered, healthy)
      })
      intercept[TimeoutException](await(fixture.request()))
      await(await(fixture.request()).body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
      await(server)
    }
  }

  "An upgraded response" should "expose protocol bytes without HTTP length validation or pool reuse" in {
    Using.resource(new Fixture()) { fixture =>
      val server = Future(blocking {
        val socket = fixture.accept()
        headers(socket)
        send(socket, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: test\r\nContent-Length: 0\r\n\r\nframes")
        socket.shutdownOutput()
        socket.getInputStream.read() shouldBe -1
        val recovered = fixture.accept()
        headers(recovered)
        send(recovered, healthy)
      })
      val response = await(fixture.request())
      response.status.code shouldBe 101
      await(response.body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "frames"
      await(await(fixture.request()).body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
      await(server)
    }
  }
}
