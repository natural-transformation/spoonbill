package spoonbill.http

import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.{KeyStore, SecureRandom}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLSocket, TrustManagerFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise, blocking}
import scala.concurrent.duration.*
import scala.util.{Try, Using}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.Reporter.PrintReporter.Implicit
import spoonbill.web.{PathAndQuery, Request, Response}

final class HttpClientResponseSafetySpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 8.seconds)
  private val healthy = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nsafe"

  private def contexts(): (SSLContext, SSLContext) = {
    val password = "spoonbill-test-only".toCharArray
    val store = KeyStore.getInstance("PKCS12")
    Using.resource(getClass.getResourceAsStream("/tls/localhost.p12"))(store.load(_, password))
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keys.init(store, password)
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trust.init(store)
    val server = SSLContext.getInstance("TLS")
    server.init(keys.getKeyManagers, null, new SecureRandom)
    val client = SSLContext.getInstance("TLS")
    client.init(null, trust.getTrustManagers, new SecureRandom)
    (server, client)
  }

  private class Fixture(tls: Boolean) extends AutoCloseable {
    private val context = contexts()
    val listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    listener.setSoTimeout(5000)
    val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    private val sockets = new java.util.concurrent.ConcurrentLinkedQueue[Socket]()
    val address = new InetSocketAddress("localhost", listener.getLocalPort)
    def accept(): Socket = {
      val raw = listener.accept()
      sockets.add(raw)
      raw.setSoTimeout(5000)
      if (!tls) raw
      else {
        val socket = context._1.getSocketFactory.createSocket(raw, "localhost", raw.getPort, true).asInstanceOf[SSLSocket]
        sockets.add(socket)
        socket.setUseClientMode(false)
        socket.setSoTimeout(5000)
        socket.startHandshake()
        socket
      }
    }
    def client(cap: Int = 128, deadline: FiniteDuration = 2.seconds, total: Int = 1,
      workers: Int = 2): HttpClient[Future, Bytes] = await(HttpClient.create[Future, Bytes](
      group = group, sslContext = context._2, maxConnectionsPerAddress = 1,
      maxTotalConnections = total, maxResponseHeaderBytes = cap,
      responseHeaderTimeout = deadline, closeNotificationWorkers = workers))
    def get(client: HttpClient[Future, Bytes], body: Stream[Future, Bytes] = Stream.empty,
      length: Option[Long] = Some(0L)): Future[Response[Stream[Future, Bytes]]] = {
      val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, length, body)
      if (tls) client.https(address, request) else client.http(address, request)
    }
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
  private def send(socket: Socket, bytes: String): Unit = {
    socket.getOutputStream.write(bytes.getBytes(US_ASCII))
    socket.getOutputStream.flush()
  }
  private def good(fixture: Fixture): Unit = {
    val socket = fixture.accept()
    headers(socket)
    send(socket, healthy)
  }
  private def assertGood(fixture: Fixture, client: HttpClient[Future, Bytes]): Unit =
    await(await(fixture.get(client)).body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"

  Seq(false, true).foreach { tls =>
    Seq("oversize", "malformed", "eof", "deadline").foreach { mode =>
      s"HTTP client response headers (TLS=$tls, $mode)" should "fail and recover a one-connection pool" in {
        Using.resource(new Fixture(tls)) { fixture =>
          val server = Future(blocking {
            val socket = fixture.accept()
            headers(socket)
            mode match {
              case "oversize" => send(socket, "HTTP/1.1 200 OK\r\nX: " + "x" * 128)
              case "malformed" => send(socket, "HTTP/1.1 200 OK\r\nMissingColon\r\n\r\n")
              case "eof" => socket.close()
              case "deadline" => send(socket, "HTTP/1.1 200 OK\r\nX: ")
            }
            if (mode != "eof") socket.getInputStream.read() shouldBe -1
            socket.close()
            good(fixture)
          })
          val client = fixture.client(deadline = if (mode == "deadline") 250.millis else 2.seconds)
          val failed = Try(await(fixture.get(client))).failed.get
          if (mode == "deadline") failed shouldBe a[TimeoutException]
          else if (mode != "eof") failed shouldBe a[IllegalArgumentException]
          assertGood(fixture, client)
          await(server)
        }
      }
    }
  }

  "HTTP client response headers" should "stop the deadline at the exact cap and preserve buffered and delayed body bytes" in {
    Using.resource(new Fixture(false)) { fixture =>
      val header = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n"
      val release = Promise[Unit]()
      val server = Future(blocking {
        val socket = fixture.accept()
        headers(socket)
        send(socket, header + "sa")
        await(release.future)
        send(socket, "fe")
        headers(socket) // Complete bodies should reuse this physical connection.
        send(socket, healthy)
      })
      val client = fixture.client(cap = header.length, deadline = 200.millis)
      val response = await(fixture.get(client))
      Thread.sleep(350)
      release.success(())
      await(response.body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
      assertGood(fixture, client)
      await(server)
    }
  }

  it should "discard when an asynchronous request writer fails before or after response headers" in {
    Seq(false, true).foreach { afterHeaders =>
      Using.resource(new Fixture(false)) { fixture =>
        val pending = Promise[Option[Bytes]]()
        val requestRead = Promise[Unit]()
        val error = new IllegalStateException("synthetic writer failure")
        val cancellations = new AtomicInteger()
        val body = new Stream[Future, Bytes] {
          def pull(): Future[Option[Bytes]] = pending.future
          def cancel(): Future[Unit] = { cancellations.incrementAndGet(); Future.unit }
        }
        val server = Future(blocking {
          val socket = fixture.accept()
          headers(socket)
          if (afterHeaders) send(socket, healthy)
          requestRead.success(())
          socket.getInputStream.read() shouldBe -1
          socket.close()
          good(fixture)
        })
        val client = fixture.client()
        val result = fixture.get(client, body, Some(1L))
        await(requestRead.future)
        val response = if (afterHeaders) Some(await(result)) else None
        pending.failure(error)
        val failed = response.fold(Try(await(result)).failed.get)(r => Try(await(r.body.fold(Bytes.empty)(_ ++ _))).failed.get)
        failed shouldBe error
        assertGood(fixture, client)
        cancellations.get() shouldBe 1
        await(server)
      }
    }
  }

  it should "publish failure before a finalizer awaiting that same response and retain its cleanup reservation" in {
    Using.resource(new Fixture(false)) { fixture =>
      val result = Promise[Response[Stream[Future, Bytes]]]()
      val pending = Promise[Option[Bytes]]()
      val cleanupStarted = Promise[Unit]()
      val release = Promise[Unit]()
      val body = new Stream[Future, Bytes] {
        def pull(): Future[Option[Bytes]] = pending.future
        def cancel(): Future[Unit] = {
          pending.trySuccess(None)
          cleanupStarted.trySuccess(())
          result.future.transformWith(_ => release.future)
        }
      }
      val server = Future(blocking {
        val socket = fixture.accept()
        headers(socket)
        send(socket, "HTTP/1.1 200 OK\r\nBad\r\n\r\n")
        socket.getInputStream.read() shouldBe -1
        socket.close()
        good(fixture)
      })
      val client = fixture.client()
      result.completeWith(fixture.get(client, body, Some(1L)))
      intercept[IllegalArgumentException](await(result.future))
      await(cleanupStarted.future)
      val next = fixture.get(client)
      next.isCompleted shouldBe false
      release.success(())
      await(await(next).body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
      await(server)
    }
  }

  it should "physically expire a second socket while the only cleanup worker is blocked" in {
    Using.resource(new Fixture(false)) { first =>
      Using.resource(new Fixture(false)) { second =>
        val entered = new CountDownLatch(1)
        val release = new CountDownLatch(1)
        val pending = Promise[Option[Bytes]]()
        val body = new Stream[Future, Bytes] {
          def pull(): Future[Option[Bytes]] = pending.future
          def cancel(): Future[Unit] = {
            entered.countDown()
            require(release.await(5, TimeUnit.SECONDS), "test cleanup release timed out")
            pending.trySuccess(None)
            Future.unit
          }
        }
        def serve(f: Fixture) = Future(blocking {
          val socket = f.accept()
          headers(socket)
          socket.getInputStream.read() shouldBe -1
          socket.close()
        })
        val firstServer = serve(first)
        val secondServer = serve(second)
        val client = first.client(deadline = 200.millis, total = 2, workers = 1)
        try {
          intercept[TimeoutException](await(first.get(client, body, Some(1L))))
          entered.await(3, TimeUnit.SECONDS) shouldBe true
          intercept[TimeoutException](await(second.get(client)))
          await(secondServer)
          await(firstServer)
        } finally release.countDown()
      }
    }
  }

  it should "discard canceled and truncated response bodies instead of reusing unread framing" in {
    Seq(false, true).foreach { truncated =>
      Using.resource(new Fixture(false)) { fixture =>
        val server = Future(blocking {
          val socket = fixture.accept()
          headers(socket)
          send(socket, "HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\nshort")
          if (truncated) socket.close()
          else socket.getInputStream.read() shouldBe -1
          good(fixture)
        })
        val client = fixture.client()
        val response = await(fixture.get(client))
        if (truncated) intercept[IllegalStateException](await(response.body.fold(Bytes.empty)(_ ++ _)))
        else await(response.body.cancel())
        assertGood(fixture, client)
        await(server)
      }
    }
  }

  it should "own output cancellation before an immediate timeout or immediate peer close" in {
    Seq(false, true).foreach { peerClose =>
      Using.resource(new Fixture(false)) { fixture =>
        val canceled = Promise[Unit]()
        val pending = Promise[Option[Bytes]]()
        val count = new AtomicInteger()
        val body = new Stream[Future, Bytes] {
          def pull(): Future[Option[Bytes]] = pending.future
          def cancel(): Future[Unit] = {
            count.incrementAndGet()
            pending.trySuccess(None)
            canceled.trySuccess(())
            Future.unit
          }
        }
        val server = Future(blocking {
          val socket = fixture.accept()
          if (!peerClose) {
            // A 1ns deadline may close before even the request header is written.
            var read = 0
            while (socket.getInputStream.read() != -1 && read < 16384) read += 1
            read should be < 16384
          }
          socket.close()
        })
        val client = fixture.client(deadline = if (peerClose) 2.seconds else 1.nano)
        val error = Try(await(fixture.get(client, body, Some(1L)))).failed.get
        if (!peerClose) error shouldBe a[TimeoutException]
        await(canceled.future)
        count.get() shouldBe 1
        await(server)
      }
    }
  }

  it should "honor Connection close even after consuming an otherwise reusable body" in {
    Using.resource(new Fixture(false)) { fixture =>
      val server = Future(blocking {
        val socket = fixture.accept()
        headers(socket)
        send(socket, "HTTP/1.1 200 OK\r\nConnection: keep-alive, CLOSE\r\nContent-Length: 4\r\n\r\nsafe")
        socket.getInputStream.read() shouldBe -1
        socket.close()
        good(fixture)
      })
      val client = fixture.client()
      assertGood(fixture, client)
      assertGood(fixture, client)
      await(server)
    }
  }
}
