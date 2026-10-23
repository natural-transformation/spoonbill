package external

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all.*
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket, SocketAddress}
import java.nio.charset.StandardCharsets.US_ASCII
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import spoonbill.cats.withDefaultRuntime
import spoonbill.data.Bytes
import spoonbill.effect.Stream
import spoonbill.http.HttpServer
import spoonbill.web.Response

final class HttpServerLifecycleIOSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private def connect(address: SocketAddress): Socket = {
    val socket = new Socket()
    socket.connect(address, 3000)
    socket.setSoTimeout(3000)
    socket
  }
  private def response(socket: Socket): String = {
    socket.getOutputStream.write("GET / HTTP/1.1\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII))
    socket.getOutputStream.flush()
    val text = new StringBuilder
    while (!text.toString.endsWith("\r\n\r\n")) {
      val next = socket.getInputStream.read()
      if (next < 0) fail("Connection closed before HTTP response")
      text.append(next.toChar)
    }
    text.toString
  }
  private def healthy(address: SocketAddress): Unit = {
    val socket = connect(address)
    try response(socket) should startWith("HTTP/1.1 200")
    finally socket.close()
  }
  private def start(address: SocketAddress, limit: Int = 2) =
    HttpServer[IO, Bytes](address, maxConnections = limit) { _ =>
      IO.pure(Response(Response.Status.Ok, Stream.empty[IO, Bytes], Nil, Some(0L)))
    }

  "A reusable HTTP server IO" should "allocate fresh admission after the previous evaluation stops" in {
    val starting = start(new InetSocketAddress("127.0.0.1", 0), limit = 1)
    val first = starting.unsafeRunSync()
    try healthy(first.localAddress)
    finally first.stopServingRequests().unsafeRunSync()
    val second = starting.unsafeRunSync()
    try healthy(second.localAddress)
    finally second.stopServingRequests().unsafeRunSync()
  }

  it should "keep connection quotas and stopping independent across concurrent evaluations" in {
    val starting = start(new InetSocketAddress("127.0.0.1", 0))
    val (first, second) = (starting, starting).parTupled.unsafeRunSync()
    val sockets = scala.collection.mutable.ArrayBuffer.empty[Socket]
    try {
      for (_ <- 1 to 2) {
        val socket = connect(first.localAddress)
        sockets += socket
        response(socket) should startWith("HTTP/1.1 200")
      }
      val secondSocket = connect(second.localAddress)
      sockets += secondSocket
      response(secondSocket) should startWith("HTTP/1.1 200")
      first.stopServingRequests().unsafeRunSync()
      val afterStop = connect(second.localAddress)
      sockets += afterStop
      response(afterStop) should startWith("HTTP/1.1 200")
    } finally {
      sockets.foreach(_.close())
      first.stopServingRequests().unsafeRunSync()
      second.stopServingRequests().unsafeRunSync()
    }
  }

  it should "retry the same IO after a failed bind without inheriting stopped admission" in {
    val held = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    val starting = start(held.getLocalSocketAddress, limit = 1)
    try starting.attempt.unsafeRunSync().isLeft shouldBe true
    finally held.close()
    val recovered = starting.unsafeRunSync()
    try healthy(recovered.localAddress)
    finally recovered.stopServingRequests().unsafeRunSync()
  }
}
