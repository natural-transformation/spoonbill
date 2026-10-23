package spoonbill.http

import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, ShutdownChannelGroupException}
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.{KeyStore, SecureRandom}
import java.util.concurrent.{Executors, TimeoutException}
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLSocket, TrustManagerFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise, blocking}
import scala.concurrent.duration.*
import scala.util.{Try, Using}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.Reporter.PrintReporter.Implicit
import spoonbill.effect.io.{RawDataSocket, SocketCloseDispatcher}
import spoonbill.web.{PathAndQuery, Request, Response}

final class HttpClientSetupDeadlineSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 8.seconds)

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

  private class Fixture extends AutoCloseable {
    val context = contexts()
    val listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    listener.setSoTimeout(5000)
    val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    val address = new InetSocketAddress("localhost", listener.getLocalPort)
    private val sockets = new java.util.concurrent.ConcurrentLinkedQueue[Socket]()
    def accept(): Socket = {
      val raw = listener.accept()
      sockets.add(raw)
      raw.setSoTimeout(5000)
      raw
    }
    def secure(raw: Socket): SSLSocket = {
      val socket = context._1.getSocketFactory.createSocket(raw, "localhost", raw.getPort, true).asInstanceOf[SSLSocket]
      sockets.add(socket)
      socket.setUseClientMode(false)
      socket.setSoTimeout(5000)
      socket.startHandshake()
      socket
    }
    def client(): HttpClient[Future, Bytes] = await(HttpClient.create[Future, Bytes](
      group = group, sslContext = context._2, maxConnectionsPerAddress = 1,
      maxTotalConnections = 1, closeNotificationWorkers = 1,
      connectionSetupTimeout = 1.second, responseHeaderTimeout = 3.seconds))
    def close(): Unit = {
      Try(listener.close())
      val iterator = sockets.iterator()
      while (iterator.hasNext) Try(iterator.next().close())
      Try(group.shutdownNow())
      ()
    }
  }

  private def get(client: HttpClient[Future, Bytes], address: InetSocketAddress): Future[Response[Stream[Future, Bytes]]] =
    client.https(address, Request(Request.Method.Get, PathAndQuery.Root, Nil, Some(0L), Stream.empty))

  private def respond(socket: Socket): Unit = {
    val read = new StringBuilder
    while (!read.toString.endsWith("\r\n\r\n") && read.length < 16384) {
      val next = socket.getInputStream.read()
      if (next < 0) fail("Client closed before request headers")
      read.append(next.toChar)
    }
    read.toString should endWith("\r\n\r\n")
    socket.getOutputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nsafe".getBytes(US_ASCII))
    socket.getOutputStream.flush()
  }

  "HTTP connection setup deadline" should "close stalled TLS and pass a one-slot pool to its queued request" in {
    Using.resource(new Fixture) { fixture =>
      val accepted = Promise[Unit]()
      val server = Future(blocking {
        val stalled = fixture.accept()
        accepted.success(())
        // Consume the ClientHello but never send a handshake record.
        val buffer = new Array[Byte](4096)
        while (stalled.getInputStream.read(buffer) != -1) ()
        stalled.close()
        val healthy = fixture.secure(fixture.accept())
        respond(healthy)
        respond(healthy) // The successful setup timer must not close this reuse.
      })
      val client = fixture.client()
      val first = get(client, fixture.address)
      await(accepted.future)
      val queued = get(client, fixture.address)
      queued.isCompleted shouldBe false
      intercept[TimeoutException](await(first)).getMessage should include("connection setup")
      await(await(queued).body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
      Thread.sleep(1100)
      await(await(get(client, fixture.address)).body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
      await(server)
    }
  }

  it should "release the global reservation for another address after stalled TLS" in {
    Using.resource(new Fixture) { stalled =>
      Using.resource(new Fixture) { healthy =>
        val firstServer = Future(blocking {
          val socket = stalled.accept()
          val buffer = new Array[Byte](4096)
          while (socket.getInputStream.read(buffer) != -1) ()
          socket.close()
        })
        val nextServer = Future(blocking { respond(healthy.secure(healthy.accept())) })
        val client = stalled.client()
        intercept[TimeoutException](await(get(client, stalled.address)))
        await(await(get(client, healthy.address)).body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
        await(firstServer)
        await(nextServer)
      }
    }
  }

  private def withPendingTcp(test: (AsynchronousSocketChannel, RawDataSocket[Future, Bytes],
    SocketCloseDispatcher, SocketCloseDispatcher.Reservation) => Unit): Unit = {
    val channel = AsynchronousSocketChannel.open()
    val raw = new RawDataSocket[Future, Bytes](channel, ByteBuffer.allocate(128), ByteBuffer.allocate(128))
    val dispatcher = new SocketCloseDispatcher(1, 1)
    val reservation = dispatcher.reserve().get
    raw.admitCloseNotifications(reservation) shouldBe true
    reservation.connectionFinished()
    try test(channel, raw, dispatcher, reservation)
    finally {
      await(raw.shutdown())
      await(reservation.awaitReleased[Future]())
      dispatcher.stopAdmission()
    }
  }

  Seq(false, true).foreach { lateFailure =>
    it should s"close a pending TCP channel and ignore late completion (failure=$lateFailure)" in {
      withPendingTcp { (channel, raw, dispatcher, reservation) =>
        val pendingConnect = Promise[Unit]()
        val completionThread = Promise[String]()
        val setup = HttpClient.withSetupDeadline(raw, reservation, 50.millis)(pendingConnect.future)
        setup.onComplete(_ => completionThread.success(Thread.currentThread().getName))(ExecutionContext.parasitic)
        intercept[TimeoutException](await(setup))
        channel.isOpen shouldBe false
        await(completionThread.future) should not startWith "spoonbill-http-assembly"
        val replacement = dispatcher.reserve().get
        try {
          if (lateFailure) pendingConnect.failure(new IllegalStateException("late connect failure"))
          else pendingConnect.success(())
          intercept[TimeoutException](await(setup))
          dispatcher.reserve() shouldBe None // Late completion must not release the replacement.
        } finally replacement.cancel()
      }
    }
  }

  it should "reject a late TCP success even when the scheduler has not fired" in {
    withPendingTcp { (channel, raw, dispatcher, reservation) =>
      val nanos = new AtomicLong(0L)
      val pendingConnect = Promise[Unit]()
      val setup = HttpClient.withSetupDeadline(raw, reservation, 10.seconds, () => nanos.get())(pendingConnect.future)
      nanos.set(11.seconds.toNanos)
      pendingConnect.success(())
      intercept[TimeoutException](await(setup))
      channel.isOpen shouldBe false
      dispatcher.reserve().get.cancel()
    }
  }

  it should "cancel a successful TCP setup timer before handing the channel to its caller" in {
    withPendingTcp { (channel, raw, dispatcher, reservation) =>
      await(HttpClient.withSetupDeadline(raw, reservation, 50.millis)(Future.unit))
      Thread.sleep(100)
      channel.isOpen shouldBe true
      dispatcher.reserve() shouldBe None
    }
  }

  it should "reject a nonpositive setup timeout" in {
    intercept[IllegalArgumentException](await(HttpClient.create[Future, Bytes](connectionSetupTimeout = Duration.Zero)))
  }

  it should "release both reservations when opening the raw channel fails" in {
    Using.resource(new Fixture) { fixture =>
      val client = fixture.client()
      fixture.group.shutdownNow()
      (1 to 2).foreach { _ =>
        intercept[ShutdownChannelGroupException](await(get(client, fixture.address)))
      }
    }
  }
}
