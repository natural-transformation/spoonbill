package spoonbill.http

import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket, SocketException, URI}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.{KeyStore, MessageDigest, SecureRandom}
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLException, SSLHandshakeException, SSLSocket, TrustManagerFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, blocking}
import scala.concurrent.duration.*
import scala.util.{Try, Using}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.Reporter.PrintReporter.Implicit
import spoonbill.http.protocol.WebSocketProtocol.Frame
import spoonbill.web.{PathAndQuery, Request}

final class HttpClientTlsSecuritySpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 8.seconds)
  private val password = "spoonbill-test-only".toCharArray

  private def store(name: String): KeyStore = {
    val value = KeyStore.getInstance("PKCS12")
    Using.resource(getClass.getResourceAsStream(s"/tls/$name.p12")) { input =>
      require(input != null, "Missing test-only TLS certificate")
      value.load(input, password)
    }
    value
  }

  private def serverContext(name: String): SSLContext = {
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keys.init(store(name), password)
    val context = SSLContext.getInstance("TLS")
    context.init(keys.getKeyManagers, null, new SecureRandom)
    context
  }

  private def clientContext(): SSLContext = {
    // Both chains are trusted: rejection must depend on peer-name validation.
    val certificates = KeyStore.getInstance("PKCS12")
    certificates.load(null, password)
    Seq("localhost" -> "localhost", "wrong-host" -> "wrong-host").foreach { case (name, alias) =>
      certificates.setCertificateEntry(name, store(name).getCertificate(alias))
    }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trust.init(certificates)
    val context = SSLContext.getInstance("TLS")
    context.init(null, trust.getTrustManagers, new SecureRandom)
    context
  }

  private def headers(socket: Socket): String = {
    val result = new StringBuilder
    while (!result.toString.endsWith("\r\n\r\n") && result.length < 16384) {
      val next = socket.getInputStream.read()
      if (next < 0) fail("Peer closed before sending HTTP headers")
      result.append(next.toChar)
    }
    result.toString should endWith("\r\n\r\n")
    result.toString
  }

  private def respond(socket: Socket, scheme: String): String = {
    val request = headers(socket)
    if (scheme == "https") {
      socket.getOutputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nsafe".getBytes(US_ASCII))
    } else {
      val key = request.split("\r\n").find(_.startsWith("Sec-WebSocket-Key: ")).get.stripPrefix("Sec-WebSocket-Key: ")
      val accept = Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-1")
        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(US_ASCII)))
      socket.getOutputStream.write((s"HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n" +
        s"Upgrade: websocket\r\nSec-WebSocket-Accept: $accept\r\n\r\n").getBytes(US_ASCII))
      socket.getOutputStream.write(Array[Byte](0x82.toByte, 4, 1, 2, 3, 4))
    }
    socket.getOutputStream.flush()
    request
  }

  private def serving(certificates: Seq[Option[String]])(handle: (Socket, Int) => Unit)(
    run: (Int, SSLContext, AsynchronousChannelGroup) => Unit
  ): Unit = {
    val listener = new ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
    listener.setSoTimeout(5000)
    val connected = new AtomicReference[Socket]()
    val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    val contexts = certificates.flatten.distinct.map(name => name -> serverContext(name)).toMap
    val server = Future(blocking {
      certificates.zipWithIndex.foreach { case (certificate, index) =>
        val raw = listener.accept()
        connected.set(raw)
        try {
          raw.setSoTimeout(5000)
          certificate match {
            case None => handle(raw, index)
            case Some(name) =>
              val tls = contexts(name).getSocketFactory.createSocket(raw, "localhost", raw.getPort, true).asInstanceOf[SSLSocket]
              connected.set(tls)
              tls.setUseClientMode(false)
              tls.setSoTimeout(5000)
              try {
                tls.startHandshake()
                handle(tls, index)
              } catch { case _: SSLException | _: SocketException if name == "wrong-host" => () }
              finally { Try(tls.close()); () }
          }
        } finally raw.close()
      }
    })
    try {
      run(listener.getLocalPort, clientContext(), group)
      await(server)
    } finally {
      Try(listener.close())
      Option(connected.get()).foreach(socket => Try(socket.close()))
      Try(group.shutdownNow())
    }
  }

  private def request(client: HttpClient[Future, Bytes], port: Int, scheme: String): Future[Unit] =
    if (scheme == "https") client(Request.Method.Get, URI.create(s"https://127.0.0.1:$port/secure"),
      Seq("Authorization" -> "Bearer synthetic-only"), Some(0L), Stream.empty).flatMap { response =>
      response.body.fold(Bytes.empty)(_ ++ _).map { bytes => bytes.asUtf8String shouldBe "safe"; () }
    }
    else client.webSocket(URI.create(s"wss://127.0.0.1:$port/secure"), Stream.empty[Future, Frame[Bytes]],
      Map("credential" -> "synthetic-only"), Map("Authorization" -> "Bearer synthetic-only")).flatMap { response =>
      response.body.pull().flatMap { frame =>
        frame shouldBe Some(Frame.Binary(Bytes(1, 2, 3, 4)))
        response.body.cancel()
      }
    }

  "WSS client" should "complete its WebSocket handshake and receive frames inside TLS" in {
    val received = new AtomicReference[String]()
    serving(Seq(Some("localhost")))((socket, _) => received.set(respond(socket, "wss"))) { (port, context, group) =>
      val client = await(HttpClient.create[Future, Bytes](sslContext = context, group = group))
      await(request(client, port, "wss"))
    }
    received.get() should include("Cookie: credential=synthetic-only")
    received.get() should include("Authorization: Bearer synthetic-only")
  }

  it should "send no HTTP headers or credentials to a plaintext responder" in {
    serving(Seq(None)) { (socket, _) =>
      val prefix = socket.getInputStream.readNBytes(5)
      prefix.length shouldBe 5
      prefix(0) shouldBe 22.toByte // TLS handshake record, never a plaintext GET.
      val length = ((prefix(3) & 0xff) << 8) | (prefix(4) & 0xff)
      length should be <= 32768
      val record = socket.getInputStream.readNBytes(length)
      record.length shouldBe length
      val raw = new String(prefix ++ record, US_ASCII)
      raw should not include "GET /"
      raw should not include "synthetic-only"
      socket.getOutputStream.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII))
      socket.getOutputStream.flush()
    } { (port, context, group) =>
      val client = await(HttpClient.create[Future, Bytes](sslContext = context, group = group))
      intercept[SSLException](await(request(client, port, "wss")))
    }
  }

  Seq("https", "wss").foreach { scheme =>
    s"$scheme peer verification" should "reject a trusted wrong-host certificate and recover a one-slot pool" in {
      val wrongHostRequests = new AtomicInteger()
      serving(Seq(Some("wrong-host"), Some("localhost"))) { (socket, index) =>
        if (index == 0) wrongHostRequests.incrementAndGet()
        respond(socket, scheme)
        ()
      } { (port, context, group) =>
        val client = await(HttpClient.create[Future, Bytes](sslContext = context, group = group, maxConnectionsPerAddress = 1))
        intercept[SSLHandshakeException](await(request(client, port, scheme)))
        wrongHostRequests.get() shouldBe 0
        await(request(client, port, scheme))
      }
    }
  }

  "TLS connection reuse" should "keep different peer names separate even when their socket addresses compare equal" in {
    val listener = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    listener.setSoTimeout(5000)
    val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    val sockets = new java.util.concurrent.ConcurrentLinkedQueue[Socket]()
    val context = serverContext("localhost")
    val reusedRequests = new AtomicInteger()
    def acceptedTls(): SSLSocket = {
      val raw = listener.accept()
      sockets.add(raw)
      val tls = context.getSocketFactory.createSocket(raw, "localhost", raw.getPort, true).asInstanceOf[SSLSocket]
      sockets.add(tls)
      tls.setUseClientMode(false)
      tls.setSoTimeout(5000)
      tls
    }
    val server = Future(blocking {
      val first = acceptedTls()
      val servingFirst = Future(blocking {
        first.startHandshake()
        respond(first, "https")
        // Keep the first TLS connection alive. A wrong-host request incorrectly
        // routed here would succeed, making the client-side rejection fail.
        try { respond(first, "https"); reusedRequests.incrementAndGet(); () }
        catch { case scala.util.control.NonFatal(_) => () }
      })
      val second = acceptedTls()
      try second.startHandshake()
      catch { case _: SSLException | _: SocketException => () }
      await(servingFirst)
    })
    try {
      val client = await(HttpClient.create[Future, Bytes](sslContext = clientContext(), group = group, maxConnectionsPerAddress = 1))
      val loopback = Array[Byte](127, 0, 0, 1)
      val good = new InetSocketAddress(InetAddress.getByAddress("localhost", loopback), listener.getLocalPort)
      val wrong = new InetSocketAddress(InetAddress.getByAddress("wrong-host.invalid", loopback), listener.getLocalPort)
      good shouldBe wrong // The old address-only pool would merge these identities.
      def get(address: InetSocketAddress) = client.https(address,
        Request(Request.Method.Get, PathAndQuery.fromString("/secure"), Nil, Some(0L), Stream.empty[Future, Bytes]))
        .flatMap(_.body.fold(Bytes.empty)(_ ++ _))
      await(get(good)).asUtf8String shouldBe "safe"
      intercept[SSLHandshakeException](await(get(wrong)))
      group.shutdownNow()
      await(server)
      reusedRequests.get() shouldBe 0
    } finally {
      Try(listener.close())
      val iterator = sockets.iterator()
      while (iterator.hasNext) Try(iterator.next().close())
      Try(group.shutdownNow())
    }
  }
}
