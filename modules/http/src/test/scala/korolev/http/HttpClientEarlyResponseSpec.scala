package spoonbill.http

import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise, blocking}
import scala.concurrent.duration.*
import scala.util.{Try, Using}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.Reporter.PrintReporter.Implicit
import spoonbill.web.{PathAndQuery, Request, Response}

final class HttpClientEarlyResponseSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private val healthy = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nsafe"

  private class Fixture extends AutoCloseable {
    val listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress)
    listener.setSoTimeout(5000)
    private val sockets = new java.util.concurrent.ConcurrentLinkedQueue[Socket]()
    private val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    private val client = await(HttpClient.create[Future, Bytes](
      group = group, maxConnectionsPerAddress = 1, maxTotalConnections = 1))
    private val address = new InetSocketAddress(InetAddress.getLoopbackAddress, listener.getLocalPort)
    def accept(): Socket = {
      val socket = listener.accept()
      sockets.add(socket)
      socket.setSoTimeout(5000)
      socket
    }
    def request(body: Stream[Future, Bytes] = Stream.empty, length: Long = 0L): Future[Response[Stream[Future, Bytes]]] =
      client.http(address, Request(Request.Method.Post, PathAndQuery.Root, Nil, Some(length), body))
    def close(): Unit = {
      Try(listener.close())
      val iterator = sockets.iterator()
      while (iterator.hasNext) Try(iterator.next().close())
      Try(group.shutdownNow())
      ()
    }
  }

  private def headers(socket: Socket): Unit = {
    val read = new StringBuilder
    while (!read.toString.endsWith("\r\n\r\n") && read.length < 16384) {
      val next = socket.getInputStream.read()
      if (next < 0) fail("Client closed before request headers")
      read.append(next.toChar)
    }
    read.toString should endWith("\r\n\r\n")
  }
  private def send(socket: Socket, value: String): Unit = {
    socket.getOutputStream.write(value.getBytes(US_ASCII))
    socket.getOutputStream.flush()
  }

  Seq(
    ("fixed", "413 Payload Too Large\r\nContent-Length: 4", "stop", "stop"),
    ("chunked", "413 Payload Too Large\r\nTransfer-Encoding: chunked", "4\r\nstop\r\n0\r\n\r\n", "stop"),
    ("bodyless", "204 No Content", "", "")
  ).foreach { case (kind, responseHead, wireBody, expectedBody) =>
    s"Early final $kind response" should "finish before upload cleanup and retain capacity until that cleanup settles" in {
      Using.resource(new Fixture) { fixture =>
        val pending = Promise[Option[Bytes]]()
        val pulling = Promise[Unit]()
        val canceled = Promise[Unit]()
        val folded = Promise[Bytes]()
        val release = Promise[Unit]()
        val physicallyClosed = Promise[Unit]()
        val cancellations = new AtomicInteger()
        val upload = new Stream[Future, Bytes] {
          def pull(): Future[Option[Bytes]] = { pulling.trySuccess(()); pending.future }
          def cancel(): Future[Unit] = {
            cancellations.incrementAndGet()
            pending.trySuccess(None)
            canceled.trySuccess(())
            // A finalizer may itself depend on the caller observing response
            // completion; neither EOF nor physical close may wait for it.
            folded.future.flatMap(_ => release.future)
          }
        }
        val server = Future(blocking {
          val socket = fixture.accept()
          headers(socket)
          await(pulling.future)
          send(socket, s"HTTP/1.1 $responseHead\r\nConnection: close\r\n\r\n$wireBody")
          socket.getInputStream.read() shouldBe -1
          physicallyClosed.success(())
          val recovered = fixture.accept()
          headers(recovered)
          send(recovered, healthy)
        })
        try {
          val response = await(fixture.request(upload, 1L))
          folded.completeWith(response.body.fold(Bytes.empty)(_ ++ _))
          Await.result(folded.future, 1.second).asAsciiString shouldBe expectedBody
          await(canceled.future)
          await(physicallyClosed.future)
          val next = fixture.request()
          next.isCompleted shouldBe false
          release.success(())
          await(await(next).body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
          await(response.body.pull()) shouldBe None
          cancellations.get() shouldBe 1
          await(server)
        } finally {
          pending.trySuccess(None)
          folded.trySuccess(Bytes.empty)
          release.trySuccess(())
        }
      }
    }
  }

  Seq(false, true).foreach { writerFails =>
    s"Early persistent final response (upload fails=$writerFails)" should "deliver EOF while deferring reuse until writer settlement" in {
      Using.resource(new Fixture) { fixture =>
        val pending = Promise[Option[Bytes]]()
        val pulling = Promise[Unit]()
        val canceled = Promise[Unit]()
        val pulls = new AtomicInteger()
        val upload = new Stream[Future, Bytes] {
          def pull(): Future[Option[Bytes]] = {
            pulling.trySuccess(())
            if (pulls.getAndIncrement() == 0) pending.future else Future.successful(None)
          }
          def cancel(): Future[Unit] = { canceled.trySuccess(()); pending.trySuccess(None); Future.unit }
        }
        val server = Future(blocking {
          val socket = fixture.accept()
          headers(socket)
          await(pulling.future)
          send(socket, "HTTP/1.1 413 Payload Too Large\r\nContent-Length: 4\r\n\r\nstop")
          val nextSocket = if (writerFails) {
            socket.getInputStream.read() shouldBe -1
            fixture.accept()
          } else {
            socket.getInputStream.read() shouldBe 'x'.toInt
            socket // Only after the complete upload can this socket be reused.
          }
          headers(nextSocket)
          send(nextSocket, healthy)
        })
        try {
          val response = await(fixture.request(upload, 1L))
          Await.result(response.body.fold(Bytes.empty)(_ ++ _), 1.second).asAsciiString shouldBe "stop"
          val next = fixture.request()
          next.isCompleted shouldBe false
          canceled.isCompleted shouldBe false
          if (writerFails) pending.failure(new IllegalStateException("late upload failure"))
          else pending.success(Some(Bytes.wrap("x".getBytes(US_ASCII))))
          await(await(next).body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
          // A later upload error must not change a completed response's EOF.
          await(response.body.pull()) shouldBe None
          if (writerFails) await(canceled.future)
          else canceled.isCompleted shouldBe false
          await(server)
        } finally pending.trySuccess(None)
      }
    }
  }
}
