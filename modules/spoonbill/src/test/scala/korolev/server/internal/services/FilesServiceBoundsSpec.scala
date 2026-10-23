package spoonbill.server.internal.services

import java.io.{ByteArrayInputStream, InputStream, IOException}
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.{JarEntry, JarOutputStream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.server.{HttpResponse, StaticResourceLimits}
import spoonbill.server.internal.SpoonbillServiceImpl
import spoonbill.web.{Headers, PathAndQuery, Request, Response}

class FilesServiceBoundsSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private val small =
    StaticResourceLimits(maxCacheEntries = 2, maxCacheBytes = 8, maxResourceBytes = 16, maxConcurrentReads = 1)
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def request(service: FilesService[Future], path: String): HttpResponse[Future] =
    await(service.resourceFromClasspath(PathAndQuery.fromString(path)))
  private def body(response: HttpResponse[Future]): String =
    await(response.body.fold("")((text, bytes) => text + bytes.asUtf8String))

  private def fixture(test: (Path, URLClassLoader) => Unit): Unit = {
    val root = Files.createTempDirectory("spoonbill-static-")
    Files.createDirectory(root.resolve("static"))
    Files.write(root.resolve("application.conf"), "private-canary".getBytes(UTF_8))
    Files.write(root.resolve("static/ok.txt"), "safe".getBytes(UTF_8))
    val loader = new URLClassLoader(Array(root.toUri.toURL), null)
    try test(root, loader)
    finally {
      loader.close()
      val paths = Files.walk(root)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder[Path]())
          .forEach { path =>
            Files.delete(path); ()
          }
      finally paths.close()
    }
  }

  "Static resources" should "retain no distinct misses through guarded and unguarded HTTP routing" in fixture {
    (_, loader) =>
      for (guarded <- Seq(false, true)) {
        val common  = new CommonService[Future]()
        val service = new FilesService[Future](common, small, path => Option(loader.getResource(path.drop(1))))
        val routes = new SpoonbillServiceImpl[Future](
          PartialFunction.empty,
          common,
          service,
          null,
          null,
          null,
          guardedSessions = guarded
        )
        for (index <- 0 until 1000) {
          val result = await(
            routes.http(
              Request(
                Request.Method.Get,
                PathAndQuery.fromString(s"/static/missing-$index"),
                Nil,
                None,
                Stream.empty[Future, Bytes]
              )
            )
          )
          result.status shouldBe Response.Status.NotFound
          service.usage shouldBe ((0, 0L, 0))
        }
      }
  }

  it should "share one cache entry across queries and collapsed separators" in fixture { (_, loader) =>
    val opens = new AtomicInteger()
    val service = new FilesService[Future](
      new CommonService[Future](),
      small,
      path => Option(loader.getResource(path.drop(1))),
      url => { opens.incrementAndGet(); url.openStream() }
    )
    for (index <- 0 until 1000) {
      val response = request(service, s"/static//ok.txt/?version=$index")
      response.status shouldBe Response.Status.Ok
      response.contentLength shouldBe Some(4L)
      body(response) shouldBe "safe"
    }
    opens.get() shouldBe 1
    service.usage shouldBe ((1, 4L, 0))
  }

  it should "enforce both LRU entry and byte budgets while serving valid assets" in fixture { (root, loader) =>
    for ((name, text) <- Seq("a.txt" -> "aaaa", "b.txt" -> "bbbb", "c.txt" -> "cccccc"))
      Files.write(root.resolve("static").resolve(name), text.getBytes(UTF_8))
    val opens = new AtomicInteger()
    val service = new FilesService[Future](
      new CommonService[Future](),
      small,
      path => Option(loader.getResource(path.drop(1))),
      url => { opens.incrementAndGet(); url.openStream() }
    )
    body(request(service, "/static/a.txt")) shouldBe "aaaa"
    body(request(service, "/static/b.txt")) shouldBe "bbbb"
    service.usage shouldBe ((2, 8L, 0))
    body(request(service, "/static/a.txt")) shouldBe "aaaa"
    opens.get() shouldBe 2
    body(request(service, "/static/c.txt")) shouldBe "cccccc"
    service.usage shouldBe ((1, 6L, 0))
    body(request(service, "/static/a.txt")) shouldBe "aaaa"
    opens.get() shouldBe 4
    service.usage shouldBe ((1, 4L, 0))
    val one = new FilesService[Future](
      new CommonService[Future](),
      small.copy(maxCacheEntries = 1, maxCacheBytes = 100),
      path => Option(loader.getResource(path.drop(1)))
    )
    request(one, "/static/a.txt")
    request(one, "/static/b.txt")
    one.usage shouldBe ((1, 4L, 0))
  }

  it should "measure actual bytes, accept the boundary, close streams, and reject one byte over" in fixture {
    (_, loader) =>
      val closed = new AtomicInteger()
      var size   = 16
      val service = new FilesService[Future](
        new CommonService[Future](),
        small.copy(maxCacheEntries = 0),
        path => Option(loader.getResource(path.drop(1))),
        _ =>
          new ByteArrayInputStream(Array.fill[Byte](size)(65)) {
            override def available(): Int = 0
            override def close(): Unit    = { closed.incrementAndGet(); super.close() }
          }
      )
      val atLimit = request(service, "/static/ok.txt")
      atLimit.status shouldBe Response.Status.Ok
      atLimit.contentLength shouldBe Some(16L)
      body(atLimit) shouldBe "A" * 16
      size = 17
      request(service, "/static/ok.txt").status.code shouldBe 503
      closed.get() shouldBe 2
      service.usage shouldBe ((0, 0L, 0))
      size = 4
      body(request(service, "/static/ok.txt")) shouldBe "AAAA"
  }

  it should "release failed lookups and close their input before a retry" in fixture { (_, loader) =>
    val closed = new AtomicInteger()
    val opens  = new AtomicInteger()
    val service = new FilesService[Future](
      new CommonService[Future](),
      small,
      path => Option(loader.getResource(path.drop(1))),
      _ => {
        if (opens.getAndIncrement() == 0) new InputStream {
          def read(): Int            = throw new IOException("fixture read failure")
          override def close(): Unit = { closed.incrementAndGet(); () }
        }
        else new ByteArrayInputStream("safe".getBytes(UTF_8))
      }
    )
    intercept[IOException](request(service, "/static/ok.txt")).getMessage shouldBe "fixture read failure"
    closed.get() shouldBe 1
    service.usage shouldBe ((0, 0L, 0))
    body(request(service, "/static/ok.txt")) shouldBe "safe"
  }

  it should "reject concurrent misses immediately without accumulating waiters and recover capacity" in fixture {
    (_, loader) =>
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val closed  = new AtomicInteger()
      val service = new FilesService[Future](
        new CommonService[Future](),
        small,
        path => Option(loader.getResource(path.drop(1))),
        _ =>
          new ByteArrayInputStream("safe".getBytes(UTF_8)) {
            override def read(buffer: Array[Byte], offset: Int, length: Int): Int = {
              entered.countDown()
              if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timed out")
              super.read(buffer, offset, length)
            }
            override def close(): Unit = { closed.incrementAndGet(); super.close() }
          }
      )
      val first = service.resourceFromClasspath(PathAndQuery.fromString("/static/ok.txt"))
      try {
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        for (index <- 0 until 100) {
          request(service, s"/static/missing-$index").status.code shouldBe 503
          service.usage shouldBe ((0, 0L, 1))
        }
        request(service, "/static/ok.txt").status.code shouldBe 503
      } finally release.countDown()
      body(await(first)) shouldBe "safe"
      closed.get() shouldBe 1
      service.usage shouldBe ((1, 4L, 0))
      request(service, "/static/missing").status shouldBe Response.Status.NotFound
  }

  private val rejected = Seq(
    "/static/../application.conf",
    "/static/./ok.txt",
    "/static/%2e%2e/application.conf",
    "/static/%252e%252e/application.conf",
    "/static/%2f..%2fapplication.conf",
    "/static/..\\application.conf",
    "/static/%5c..%5capplication.conf",
    "/static/ok.txt%00",
    "/static/ok.txt#fragment",
    "/static/ok.txt\u0000",
    "/application.conf",
    "/static/" + "x" * 2048
  )

  it should "reject traversal and ambiguous paths before lookup in directory and JAR packaging" in fixture {
    (root, directory) =>
      val jar = root.resolve("fixture.jar")
      val out = new JarOutputStream(Files.newOutputStream(jar))
      try {
        for ((name, text) <- Seq("static/ok.txt" -> "safe", "application.conf" -> "private-canary")) {
          out.putNextEntry(new JarEntry(name))
          out.write(text.getBytes(UTF_8))
          out.closeEntry()
        }
      } finally out.close()
      val archive = new URLClassLoader(Array(jar.toUri.toURL), null)
      try
        for (loader <- Seq(directory, archive)) {
          val lookups = new AtomicInteger()
          val service = new FilesService[Future](
            new CommonService[Future](),
            small,
            path => { lookups.incrementAndGet(); Option(loader.getResource(path.drop(1))) }
          )
          for (path <- rejected) request(service, path).status shouldBe Response.Status.NotFound
          val segmentSlash = PathAndQuery.Root / "static" / "../application.conf"
          await(service.resourceFromClasspath(segmentSlash)).status shouldBe Response.Status.NotFound
          lookups.get() shouldBe 0
          body(request(service, "/static/ok.txt")) shouldBe "safe"
          service.usage shouldBe ((1, 4L, 0))
        }
      finally archive.close()
  }

  it should "reject symlink escapes and symlink aliases within the public root" in fixture { (root, loader) =>
    Files.createSymbolicLink(root.resolve("static/escape.txt"), root.resolve("application.conf"))
    Files.createSymbolicLink(root.resolve("static/alias.txt"), root.resolve("static/ok.txt"))
    val service =
      new FilesService[Future](new CommonService[Future](), small, path => Option(loader.getResource(path.drop(1))))
    request(service, "/static/escape.txt").status shouldBe Response.Status.NotFound
    request(service, "/static/alias.txt").status shouldBe Response.Status.NotFound
    body(request(service, "/static/ok.txt")) shouldBe "safe"
  }
}
