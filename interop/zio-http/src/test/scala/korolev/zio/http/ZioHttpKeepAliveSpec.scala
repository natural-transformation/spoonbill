package spoonbill.zio.http

import RawHttpConnection.RawResponse
import ZioHttpKeepAliveFixture.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.data.Bytes
import spoonbill.effect.Stream
import spoonbill.server.{HttpRequest, HttpResponse}
import spoonbill.web.PathAndQuery.{/, Root}
import spoonbill.web.Response as SpoonbillResponse
import zio.{Duration, Promise, Task, Unsafe, ZIO}
import zio.http.{Path, Request, URL}

/**
 * Connection-level regression for zero-length responses on ZIO HTTP.
 *
 * Every scenario reuses one raw socket: a successful `GET /health` after the
 * first response proves that the response completed and left HTTP/1.1
 * keep-alive usable. A read timeout fails the scenario.
 */
final class ZioHttpKeepAliveSpec extends AnyFlatSpec with Matchers {

  private def run[A](program: Task[A]): A =
    Unsafe.unsafe(implicit unsafe => runtime.unsafe.run(program).getOrThrowFiberFailure())

  private val plainText = "Content-Type" -> "text/plain"

  private def handle(): Array[Byte] = UUID.randomUUID().toString.getBytes(StandardCharsets.US_ASCII)

  /**
   * Send the given exchanges on one connection, then require a healthy
   * successor response.
   */
  private def onOneSocket(
    config: spoonbill.server.SpoonbillServiceConfig[AppTask, String, Any] = ZioHttpKeepAliveFixture.config()
  )(exchanges: (RawHttpConnection => Task[RawResponse])*): (Vector[RawResponse], RawResponse) =
    run(withServer(routes(config)) { port =>
      RawHttpConnection.open(port).flatMap { connection =>
        for {
          responses <- ZIO.foreach(exchanges.toVector)(_(connection))
          health    <- connection.exchange("GET", "/health")
        } yield (responses, health)
      }
    })

  private def assertHealthy(health: RawResponse): Unit = {
    health.status shouldBe 200
    health.text shouldBe "ok"
  }

  private def get(path: String): RawHttpConnection => Task[RawResponse] = _.exchange("GET", path)

  "Authentication completion" should "return 204 with the session cookie and keep the connection usable" in {
    val (Vector(completed), health) = onOneSocket()(
      _.exchange("POST", "/auth/complete", authHeaders(plainText), handle())
    ): @unchecked
    completed.status shouldBe 204
    completed.all("Set-Cookie") should contain(s"$SessionCookie=$Token; Path=/; HttpOnly; SameSite=Lax; Max-Age=60")
    completed.header("Cache-Control") shouldBe Some("no-store")
    completed.header("Pragma") shouldBe Some("no-cache")
    assertHealthy(health)
  }

  it should "reject a consumed-handle replay with 403 after reading its body" in {
    val replayed = handle()
    val (Vector(first, replay), health) = onOneSocket()(
      _.exchange("POST", "/auth/complete", authHeaders(plainText), replayed),
      _.exchange("POST", "/auth/complete", authHeaders(plainText), replayed)
    ): @unchecked
    first.status shouldBe 204
    replay.status shouldBe 403
    replay.all("Set-Cookie") shouldBe empty
    replay.header("Cache-Control") shouldBe Some("no-store")
    assertHealthy(health)
  }

  it should "reject a foreign origin early, without reading its body, and keep the connection usable" in {
    val (Vector(rejected), health) = onOneSocket()(
      _.exchange("POST", "/auth/complete", Seq("Origin" -> "http://invalid.example", plainText), handle())
    ): @unchecked
    rejected.status shouldBe 403
    rejected.all("Set-Cookie") shouldBe empty
    assertHealthy(health)
  }

  "Logout" should "redirect with 303, expire the session cookie and keep the connection usable" in {
    val (Vector(logout), health) = onOneSocket()(
      _.exchange("POST", "/auth/logout", authHeaders("Cookie" -> s"$BindingCookie=$Binding; $SessionCookie=$Token"))
    ): @unchecked
    logout.status shouldBe 303
    logout.header("Location") shouldBe Some("/")
    logout.all("Set-Cookie") should contain(s"$SessionCookie=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0")
    logout.header("Cache-Control") shouldBe Some("no-store")
    assertHealthy(health)
  }

  it should "return 204 without a redirect and keep the connection usable" in {
    val (Vector(logout), health) = onOneSocket(ZioHttpKeepAliveFixture.config(logoutRedirect = None))(
      _.exchange("POST", "/auth/logout", authHeaders())
    ): @unchecked
    logout.status shouldBe 204
    logout.header("Location") shouldBe None
    logout.all("Set-Cookie") should contain(s"$SessionCookie=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0")
    assertHealthy(health)
  }

  "Ordinary zero-length responses" should "complete for 200 and 410, from empty streams and empty Bytes" in {
    for {
      (path, status) <- Seq(
                          "/empty/200/stream-empty" -> 200,
                          "/empty/200/empty-bytes"  -> 200,
                          "/empty/410/stream-empty" -> 410,
                          "/empty/410/empty-bytes"  -> 410
                        )
    } {
      val (Vector(blank), health) = onOneSocket()(get(path)): @unchecked
      withClue(path) {
        blank.status shouldBe status
        blank.body shouldBe empty
        blank.header("Content-Length").map(_.trim) shouldBe Some("0")
        blank.header("X-Test") shouldBe Some("kept")
        assertHealthy(health)
      }
    }
  }

  they should "preserve redirect metadata" in {
    val (Vector(redirect), health) = onOneSocket()(get("/redirect")): @unchecked
    redirect.status shouldBe 303
    redirect.header("Location") shouldBe Some("/health")
    redirect.all("Set-Cookie") shouldBe Seq("custom=1; Path=/")
    redirect.header("Cache-Control") shouldBe Some("no-store")
    assertHealthy(health)
  }

  "Known-length responses" should "stream every chunk with exact framing" in {
    val (Vector(known), health) = onOneSocket()(get("/known")): @unchecked
    known.status shouldBe 200
    known.chunked shouldBe false
    known.header("Content-Length").map(_.trim) shouldBe Some("16")
    known.text shouldBe "alpha-beta-gamma"
    known.header("X-Test") shouldBe Some("kept")
    assertHealthy(health)
  }

  "Unknown-length responses" should "use valid chunked framing when nonempty and when empty" in {
    val (Vector(nonempty, blank), health) = onOneSocket()(get("/chunked"), get("/chunked-empty")): @unchecked
    nonempty.status shouldBe 200
    nonempty.chunked shouldBe true
    nonempty.text shouldBe "one-two-three"
    blank.status shouldBe 200
    blank.body shouldBe empty
    assertHealthy(health)
  }

  "A discarded zero-length stream" should "release its acquired resource once without being pulled" in {
    val resource                   = new TrackedResource
    val (Vector(response), health) = onOneSocket(ZioHttpKeepAliveFixture.config(resource))(get("/resource")): @unchecked
    response.status shouldBe 200
    assertHealthy(health)
    resource.acquired.get() shouldBe 1
    resource.released.get() shouldBe 1
    resource.pulls.get() shouldBe 0
  }

  private def single(response: AppTask[HttpResponse[AppTask]]) =
    ZioHttpKeepAliveFixture.config(http = Some(({
      case request if request.pq.asPath == Root / "zero" => response
    }: PartialFunction[HttpRequest[AppTask], AppTask[HttpResponse[AppTask]]]).orElse(handlers(new TrackedResource))))

  private val zeroRequest = Request.get(URL(Path.decode("/zero")))

  private def zeroLength(onCancel: => AppTask[Unit]): HttpResponse[AppTask] =
    SpoonbillResponse(
      SpoonbillResponse.Status.Ok,
      new Stream[AppTask, Bytes] {
        def pull(): AppTask[Option[Bytes]] =
          ZIO.fail(new IllegalStateException("A zero-length body must not be pulled"))
        def cancel(): AppTask[Unit] = onCancel
      },
      Nil,
      Some(0L)
    )

  it should "surface a cleanup failure instead of returning the empty response" in {
    val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
    val config = single(ZIO.succeed(zeroLength {
      attempts.incrementAndGet()
      ZIO.fail(new IllegalStateException("cleanup failed"))
    }))
    val (Vector(failed), health) = onOneSocket(config)(get("/zero")): @unchecked
    failed.status shouldBe 500
    attempts.get() shouldBe 1
    assertHealthy(health)
  }

  it should "finish cleanup when the request is interrupted during cancellation" in {
    val program = for {
      started  <- Promise.make[Nothing, Unit]
      gate     <- Promise.make[Nothing, Unit]
      released <- zio.Ref.make(0)
      app =
        routes(single(ZIO.succeed(zeroLength(started.succeed(()) *> gate.await *> released.update(_ + 1))))).toHandler
      request   <- ZIO.scoped(app.runZIO(zeroRequest)).fork
      _         <- started.await
      interrupt <- request.interrupt.fork
      early     <- interrupt.join.timeout(Duration.fromMillis(100))
      _         <- gate.succeed(())
      _         <- interrupt.join
      count     <- released.get
    } yield (early.isEmpty, count)
    run(
      program.timeoutFail(new RuntimeException("Interrupted cleanup did not settle"))(Duration.fromSeconds(5))
    ) shouldBe
      ((true, 1))
  }

  it should "own the stream from the moment the handler returns it, even with interruption pending" in {
    val resource         = new TrackedResource
    val handlerReturning = new CountDownLatch(1)
    val interruptQueued  = new CountDownLatch(1)
    // The handler acquires and returns in one operation, so any leak belongs to the adapter.
    val handler: AppTask[HttpResponse[AppTask]] = resourceStream(resource).flatMap { stream =>
      ZIO.succeed {
        handlerReturning.countDown()
        interruptQueued.await(5, TimeUnit.SECONDS)
        SpoonbillResponse(SpoonbillResponse.Status.Ok, stream, Nil, Some(0L))
      }
    }
    val program = for {
      request <- ZIO.scoped(routes(single(handler)).toHandler.runZIO(zeroRequest)).fork
      _       <- ZIO.attemptBlocking(handlerReturning.await(5, TimeUnit.SECONDS))
      // Queue the interruption synchronously before the handler's value is delivered.
      _    <- request.interruptAsFork(zio.FiberId.None)
      _    <- ZIO.succeed(interruptQueued.countDown())
      exit <- request.await
    } yield exit
    val exit = run(program.timeoutFail(new RuntimeException("Handoff did not settle"))(Duration.fromSeconds(5)))
    withClue(exit)(exit.isInterrupted shouldBe true)
    resource.acquired.get() shouldBe 1
    resource.released.get() shouldBe 1
    resource.pulls.get() shouldBe 0
  }
}
