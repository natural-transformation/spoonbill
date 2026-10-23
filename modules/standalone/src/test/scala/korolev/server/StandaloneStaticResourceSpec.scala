package spoonbill.server.internal.services

import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, Socket, SocketAddress, SocketTimeoutException, URLClassLoader}
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID
import java.util.jar.{JarEntry, JarOutputStream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.server.*
import spoonbill.server.internal.SpoonbillServiceImpl
import spoonbill.state.javaSerialization.*

final class StandaloneStaticResourceSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 8.seconds)

  private def get(address: SocketAddress, target: String): (Int, String) = {
    val socket = new Socket()
    try {
      socket.connect(address, 3000)
      socket.setSoTimeout(5000)
      socket.getOutputStream.write(s"GET $target HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII))
      socket.getOutputStream.flush()
      val input = socket.getInputStream
      val deadline = System.nanoTime() + 5.seconds.toNanos
      def remaining(): Unit = {
        val nanos = deadline - System.nanoTime()
        if (nanos <= 0) fail(s"Response deadline exceeded for $target")
        socket.setSoTimeout(math.max(1L, nanos / 1000000L).toInt)
      }
      def line(limit: Int): String = {
        val value = new StringBuilder
        while (!value.toString.endsWith("\r\n") && value.length < limit) {
          remaining()
          val next = input.read()
          if (next < 0) fail(s"Incomplete HTTP framing for $target")
          value.append(next.toChar)
        }
        if (!value.toString.endsWith("\r\n")) fail(s"HTTP line limit exceeded for $target")
        value.toString.dropRight(2)
      }
      val status = line(1024)
      val headers = Vector.newBuilder[(String, String)]
      var headerBytes = status.length + 2
      var field = line(16384 - headerBytes)
      while (field.nonEmpty) {
        headerBytes += field.length + 2
        val split = field.indexOf(':')
        if (split < 1) fail(s"Malformed HTTP header for $target")
        headers += (field.take(split).toLowerCase(java.util.Locale.ROOT) -> field.drop(split + 1).trim)
        field = line(16384 - headerBytes)
      }
      val fields = headers.result().toMap
      val maximum = 4 * 1024 * 1024
      def exact(length: Int): Array[Byte] = {
        require(length >= 0 && length <= maximum, "Fixture response exceeded its byte budget")
        val bytes = new Array[Byte](length)
        var offset = 0
        while (offset < length) {
          remaining()
          val count = try input.read(bytes, offset, length - offset)
          catch { case _: SocketTimeoutException => fail(s"Truncated response for $target: received $offset of $length declared bytes ($status)") }
          if (count < 0) fail(s"Truncated response for $target: received $offset of $length declared bytes ($status)")
          offset += count
        }
        bytes
      }
      val body = fields.get("transfer-encoding") match {
        case Some(value) if value.equalsIgnoreCase("chunked") =>
          val output = new ByteArrayOutputStream
          var size = Integer.parseInt(line(1024).takeWhile(_ != ';'), 16)
          while (size != 0) {
            require(size > 0 && size <= maximum - output.size(), "Fixture chunks exceeded their byte budget")
            output.write(exact(size))
            new String(exact(2), US_ASCII) shouldBe "\r\n"
            size = Integer.parseInt(line(1024).takeWhile(_ != ';'), 16)
          }
          var trailer = line(16384)
          var trailerBytes = trailer.length + 2
          while (trailer.nonEmpty) {
            trailer = line(16384 - trailerBytes)
            trailerBytes += trailer.length + 2
          }
          output.toByteArray
        case Some(value) => fail(s"Unsupported fixture transfer encoding: $value")
        case None => exact(fields.getOrElse("content-length", fail(s"Missing response framing for $target")).toInt)
      }
      status.split(' ')(1).toInt -> new String(body, UTF_8)
    } finally socket.close()
  }

  private def serving(service: SpoonbillService[Future])(run: SocketAddress => Unit): Unit = {
    val handler = await(standalone.buildServer[Future, Array[Byte]](
      service, new InetSocketAddress("127.0.0.1", 0), gracefulShutdown = false
    ))
    try run(handler.localAddress)
    finally await(handler.stopServingRequests())
  }

  "Static routes over standalone HTTP" should "survive misses with installed browser authentication and an unguarded configuration" in {
    Seq(false, true).foreach { guarded =>
      val configuration = BrowserConfiguration(BrowserProviderScope("static-adapter", "browser"), Set("http://localhost"), secureCookies = false)
      val provider = new InMemoryBrowserPersistence[Future, Unit, String](
        (), new InMemoryBrowserPersistence.Host[Unit, String] {
          def resolve(state: Unit, sessionId: UUID, now: Instant): Option[HostPrincipal[String]] = None
        }, () => Instant.now(), () => UUID.randomUUID(), scope = configuration.scope
      )
      val browser = new BrowserAuthentication[Future, String, String](provider,
        BrowserPresentation(() => "public", (_, _) => true, (state, _, _) => state), configuration)
      val base = SpoonbillServiceConfig[Future, String, Any](
        stateLoader = StateLoader.default[Future, String]("public"),
        staticResourceLimits = StaticResourceLimits(maxCacheEntries = 2, maxCacheBytes = 32)
      )
      try serving(spoonbillService(if (guarded) browser.configure(base) else base)) { address =>
        for (index <- 0 until 40) get(address, s"/static/absent-$index") shouldBe (404 -> "Not found")
        val clientAsset = get(address, "/static/spoonbill-client.min.js")
        clientAsset._1 shouldBe 200
        clientAsset._2.length should be > 32
        await(provider.resources).bindings shouldBe 0
        await(provider.resources).views shouldBe 0
      } finally await(browser.close())
    }
  }

  it should "contain raw traversal paths and share accepted aliases in directory and JAR packaging" in {
    val root = Files.createTempDirectory("spoonbill-static-http-")
    Files.createDirectory(root.resolve("static"))
    Files.write(root.resolve("static/ok.txt"), "public-canary".getBytes(UTF_8))
    Files.write(root.resolve("application.conf"), "private-canary".getBytes(UTF_8))
    val jar = root.resolve("fixture.jar")
    val output = new JarOutputStream(Files.newOutputStream(jar))
    try Seq("static/ok.txt" -> "public-canary", "application.conf" -> "private-canary").foreach { case (name, body) =>
      output.putNextEntry(new JarEntry(name))
      output.write(body.getBytes(UTF_8))
      output.closeEntry()
    } finally output.close()
    try Seq(root.toUri.toURL, jar.toUri.toURL).foreach { location =>
      val loader = new URLClassLoader(Array(location), null)
      try {
        val common = new CommonService[Future]()
        val files = new FilesService[Future](common,
          StaticResourceLimits(maxCacheEntries = 1, maxCacheBytes = 32, maxResourceBytes = 64),
          path => Option(loader.getResource(path.drop(1))))
        val service = new SpoonbillServiceImpl[Future](PartialFunction.empty, common, files, null, null, null)
        serving(service) { address =>
          Seq("/static/../application.conf", "/static/%2e%2e/application.conf", "/static/%2E%2e/application.conf",
            "/static/%252e%252e/application.conf", "/static/%2f..%2fapplication.conf", "/static/..\\application.conf",
            "/static/%5c..%5capplication.conf", "/static/ok.txt%00").foreach { target =>
            val response = get(address, target)
            response shouldBe (404 -> "Not found")
            response._2 should not include "private-canary"
            files.usage shouldBe ((0, 0L, 0))
          }
          for (index <- 0 until 40) get(address, s"/static/missing-$index") shouldBe (404 -> "Not found")
          files.usage shouldBe ((0, 0L, 0))
          for (index <- 0 until 20) get(address, s"/static//ok.txt/?version=$index") shouldBe (200 -> "public-canary")
          files.usage shouldBe ((1, 13L, 0))
        }
      } finally loader.close()
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(path => { Files.delete(path); () })
      finally paths.close()
    }
  }
}
