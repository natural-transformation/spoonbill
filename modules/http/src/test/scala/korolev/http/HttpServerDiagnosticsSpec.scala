package spoonbill.http

import java.net.{InetSocketAddress, Socket, SocketException}
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.web.{Request, Response}
import spoonbill.http.protocol.Http11

final class HttpServerDiagnosticsSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def closed(socket: Socket): Unit =
    try socket.getInputStream.read() shouldBe -1
    catch { case _: SocketException => () }
  private def headers(socket: Socket): String = {
    val result = new StringBuilder
    while (!result.toString.endsWith("\r\n\r\n")) {
      val byte = socket.getInputStream.read()
      if (byte == -1) fail("Closed before response")
      result.append(byte.toChar)
    }
    result.toString
  }

  "HTTP diagnostics" should "reject malformed and expired bodies without reporting application failures" in {
    val reported = new ConcurrentLinkedQueue[Throwable]()
    given ExecutionContext = new ExecutionContext {
      def execute(task: Runnable): Unit = ExecutionContext.global.execute(task)
      def reportFailure(error: Throwable): Unit = { reported.add(error); () }
    }
    val applicationFailure = new IllegalStateException("Synthetic application failure")
    val applicationInputClass = new Http11.InvalidFraming("Application-origin framing exception")
    val applicationIoFailure = new java.io.IOException("Application I/O failure")
    val server = await(HttpServer[Future, Bytes](new InetSocketAddress("127.0.0.1", 0),
      gracefulShutdown = true, maxChunkLineBytes = 16, requestBodyTimeout = 100.millis) { request =>
      request.pq.mkString match {
        case "/application" => Future.failed(applicationFailure)
        case "/application-framing" => Future.failed(applicationInputClass)
        case "/application-io" => Future.failed(applicationIoFailure)
        case _ => request.body.foreach(_ => Future.unit)
          .map(_ => Response(Response.Status.Ok, Stream.empty[Future, Bytes], Nil, Some(0L)))
      }
    })
    def connect(): Socket = {
      val socket = new Socket()
      socket.connect(server.localAddress, 3000)
      socket.setSoTimeout(2000)
      socket
    }
    def write(socket: Socket, value: String): Unit = {
      socket.getOutputStream.write(value.getBytes(US_ASCII)); socket.getOutputStream.flush()
    }
    try {
      val prefix = "POST /reading HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
      for (index <- 0 until 30) {
        val socket = connect()
        try {
          write(socket, prefix + (if (index % 2 == 0) "xyz\r\n" else "f" * 17))
          closed(socket)
        } finally socket.close()
      }
      Seq(prefix + "4\r\na", "POST /reading HTTP/1.1\r\nContent-Length: 4\r\n\r\na").foreach { incomplete =>
        val socket = connect()
        try { write(socket, incomplete); closed(socket) }
        finally socket.close()
      }
      val truncated = connect()
      try {
        write(truncated, "POST /reading HTTP/1.1\r\nContent-Length: 4\r\n\r\na")
        truncated.shutdownOutput()
        closed(truncated)
      } finally truncated.close()
      reported.asScala.toVector shouldBe empty

      Seq("/application", "/application-framing", "/application-io").foreach { path =>
        val socket = connect()
        try {
          write(socket, s"GET $path HTTP/1.1\r\n\r\n")
          headers(socket) should startWith("HTTP/1.1 500")
        } finally socket.close()
      }
      reported.asScala.toVector shouldBe Vector(applicationFailure, applicationInputClass, applicationIoFailure)
      val healthy = connect()
      try {
        write(healthy, "GET /healthy HTTP/1.1\r\n\r\n")
        headers(healthy) should startWith("HTTP/1.1 200")
      } finally healthy.close()
    } finally {
      await(server.stopServingRequests())
      // Aborted request work is retained as the graceful terminal result; wait
      // for all requests to settle before checking the captured diagnostics.
      Await.ready(server.awaitShutdown(), 5.seconds)
    }
    reported.asScala.toVector shouldBe Vector(applicationFailure, applicationInputClass, applicationIoFailure)
  }
}
