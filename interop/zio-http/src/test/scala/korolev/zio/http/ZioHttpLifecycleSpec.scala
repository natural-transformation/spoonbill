package spoonbill.zio.http

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.Qsid
import spoonbill.data.Bytes
import spoonbill.effect.Stream
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{
  AuthenticationCompletionConfig,
  SessionAccessControl,
  SessionGuard,
  SpoonbillServiceConfig,
  StateLoader,
  WebSocketResponse
}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*
import spoonbill.web.{Request => SpoonbillRequest}
import spoonbill.zio.Zio2Effect
import zio.http.*
import zio.{Chunk, Duration, Exit, FiberRef, NonEmptyChunk, Ref, RIO, Runtime, Unsafe, ZIO}

import scala.concurrent.ExecutionContext

final class ZioHttpLifecycleSpec extends AnyFlatSpec with Matchers {
  private type AppTask[A] = RIO[Any, A]
  implicit private val runtime: Runtime[Any] = Runtime.default
  implicit private val ec: ExecutionContext = Runtime.defaultExecutor.asExecutionContext
  implicit private val effect: Zio2Effect[Any, Throwable] = new Zio2Effect[Any, Throwable](runtime, identity, identity)

  "releaseOnce" should "execute cleanup with the calling effect's fiber-local context" in {
    val program = ZIO.scoped {
      for {
        context <- FiberRef.make("default")
        observed <- Ref.make("not-run")
        release = WebSocketResponse.releaseOnce[AppTask](() => context.get.flatMap(observed.set))
        _ <- context.locally("request-context")(release())
        result <- observed.get
      } yield result
    }
    val outcome = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(program) }
    outcome shouldBe Exit.succeed("request-context")
  }

  it should "finish the single cleanup attempt when its first caller is interrupted" in {
    val program = for {
      started <- zio.Promise.make[Nothing, Unit]
      finish <- zio.Promise.make[Nothing, Unit]
      completed <- Ref.make(0)
      release = WebSocketResponse.releaseOnce[AppTask](() =>
                  started.succeed(()).unit *> finish.await *> completed.update(_ + 1)
                )
      owner <- release().fork
      _ <- started.await
      interrupt <- owner.interrupt.fork
      // The interruption request is delivered before opening the cleanup gate.
      // A masked cleanup keeps the interruption pending until it finishes.
      interruptedEarly <- interrupt.join.timeout(Duration.fromMillis(100))
      _ <- finish.succeed(())
      _ <- interrupt.join
      _ <- release().timeoutFail(new RuntimeException("Release remained in Running"))(Duration.fromSeconds(1))
      runs <- completed.get
    } yield (interruptedEarly.isEmpty, runs)
    val outcome = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(program) }
    outcome shouldBe Exit.succeed((true, 1))
  }

  it should "share a pending cleanup with concurrent lazy callers" in {
    val program = for {
      started <- zio.Promise.make[Nothing, Unit]
      finish <- zio.Promise.make[Nothing, Unit]
      runs <- Ref.make(0)
      release = WebSocketResponse.releaseOnce[AppTask](() =>
                  runs.update(_ + 1) *> started.succeed(()).unit *> finish.await
                )
      first <- release().fork
      _ <- started.await
      second <- release().fork
      pending <- second.poll
      _ <- finish.succeed(())
      _ <- first.join.zipPar(second.join)
      count <- runs.get
    } yield (pending.isEmpty, count)
    val outcome = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(program) }
    outcome shouldBe Exit.succeed((true, 1))
  }

  it should "publish recovered failures to later callers without retrying cleanup" in {
    val attempts = new AtomicInteger(0)
    val release = WebSocketResponse.releaseOnce[AppTask](() =>
      ZIO.succeed(attempts.incrementAndGet()) *> ZIO.fail(new IllegalStateException("cleanup failed"))
    )
    val result = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(release() *> release()) }
    result shouldBe Exit.succeed(())
    attempts.get() shouldBe 1
  }

  "withOutput" should "release the original response when replacement cancel throws" in {
    val originalReleases = new AtomicInteger(0)
    val response = WebSocketResponse.SendThenClose[AppTask](
      Stream.empty[AppTask, Bytes],
      "json",
      WebSocketResponse.releaseOnce[AppTask](() => ZIO.succeed { originalReleases.incrementAndGet(); () })
    )
    val replacement = new Stream[AppTask, Bytes] {
      def pull(): AppTask[Option[Bytes]] = ZIO.none
      def cancel(): AppTask[Unit] = throw new IllegalStateException("cancel construction failed")
    }
    val outcome = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(response.withOutput(replacement).release().either) }
    outcome shouldBe a[Exit.Success[?]]
    originalReleases.get() shouldBe 1
  }

  "WebSocketResponse.release" should "stay unevaluated until the lazy effect runs" in {
    val runs = new AtomicInteger(0)
    val response = WebSocketResponse.SendThenClose[AppTask](
      Stream.empty[AppTask, Bytes],
      "json",
      WebSocketResponse.releaseOnce[AppTask](() => ZIO.succeed { runs.incrementAndGet(); () })
    )
    runs.get() shouldBe 0
    val pending = response.release()
    runs.get() shouldBe 0
    val outcome = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(pending) }
    outcome shouldBe a[Exit.Success[?]]
    runs.get() shouldBe 1
    val again = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(response.release()) }
    again shouldBe a[Exit.Success[?]]
    runs.get() shouldBe 1
  }

  it should "run one lazy cleanup when the same effect is replayed" in {
    val runs = new AtomicInteger(0)
    val response = WebSocketResponse.SendThenClose[AppTask](
      Stream.empty[AppTask, Bytes],
      "json",
      WebSocketResponse.releaseOnce[AppTask](() => ZIO.succeed { runs.incrementAndGet(); () })
    )
    val close = response.release()
    val outcome = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(close *> close) }
    outcome shouldBe a[Exit.Success[?]]
    runs.get() shouldBe 1
  }

  it should "keep cleanup available after an unexecuted lazy release" in {
    val runs = new AtomicInteger(0)
    val response = WebSocketResponse.SendThenClose[AppTask](
      Stream.empty[AppTask, Bytes],
      "json",
      WebSocketResponse.releaseOnce[AppTask](() => ZIO.succeed { runs.incrementAndGet(); () })
    )
    val unused = response.release()
    runs.get() shouldBe 0
    val outcome = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(response.release()) }
    outcome shouldBe a[Exit.Success[?]]
    runs.get() shouldBe 1
    val late = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(unused) }
    late shouldBe a[Exit.Success[?]]
    runs.get() shouldBe 1
  }

  it should "cancel a transformed stream when the response is released before pull" in {
    val wrapperCancels = new AtomicInteger(0)
    val response = WebSocketResponse.SendThenClose[AppTask](
      Stream.empty[AppTask, Bytes],
      "json",
      WebSocketResponse.releaseOnce[AppTask](() => ZIO.unit)
    )
    val wrapped = response.mapOutput { output =>
      new Stream[AppTask, Bytes] {
        def pull(): AppTask[Option[Bytes]] = output.pull()
        def cancel(): AppTask[Unit] = ZIO.succeed { wrapperCancels.incrementAndGet(); () }
      }
    }
    val outcome = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(wrapped.release()) }
    outcome shouldBe a[Exit.Success[?]]
    wrapperCancels.get() shouldBe 1
  }

  "A guarded missing view" should "deliver one reload frame and then close the socket" in {
    val opened = new AtomicInteger(0)
    val resumed = new AtomicInteger(0)
    val control = new SessionAccessControl[AppTask, String] {
      def authorizeHttp(request: SpoonbillRequest.Head, state: String): AppTask[Unit] = ZIO.unit
      def open(qsid: Qsid, request: SpoonbillRequest.Head, connectionId: ConnectionId): AppTask[SessionGuard[AppTask, String]] = {
        opened.incrementAndGet()
        ZIO.fail(new IllegalStateException("An absent local view must not open a guard"))
      }
      override def resume(
        qsid: Qsid,
        request: SpoonbillRequest.Head,
        connectionId: ConnectionId
      ): Option[AppTask[SessionGuard[AppTask, String]]] = {
        resumed.incrementAndGet()
        None
      }
    }
    val config = SpoonbillServiceConfig[AppTask, String, Any](
      stateLoader = StateLoader[AppTask, String]((_, _) => ZIO.fail(new IllegalStateException("no rebuild"))),
      stateStorage = StateStorage.ephemeral[AppTask, String](16),
      sessionAccessControl = Some(control),
      webSocketProtocolsEnabled = false,
      authenticationCompletion = Some(AuthenticationCompletionConfig[AppTask](
        bindingCookieName = "test-binding",
        sessionCookieName = "test-session",
        allowedOrigins = Set("http://localhost"),
        secureCookies = false,
        cookieMaxAgeSeconds = 60L,
        deliver = (_, _) => ZIO.succeed(None),
        logout = (_, _) => ZIO.unit
      ))
    )
    val routes = new ZioHttpSpoonbill[Any].service(config)
    val program = ZIO.scoped {
      for {
        port <- Server.install(routes)
        frames <- zio.Ref.make(Vector.empty[String])
        terminated <- zio.Promise.make[Nothing, Unit]
        socket = Handler.webSocket { channel =>
                   channel.receiveAll {
                     case ChannelEvent.Read(WebSocketFrame.Binary(bytes)) =>
                       frames.update(_ :+ new String(bytes.toArray, StandardCharsets.UTF_8))
                     case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
                       frames.update(_ :+ text)
                     case ChannelEvent.Read(WebSocketFrame.Close(_, _)) =>
                       terminated.succeed(()).unit
                     case ChannelEvent.Unregistered =>
                       terminated.succeed(()).unit
                     case _ => ZIO.unit
                   }.ensuring(terminated.succeed(()).ignore)
                 }
        headers = Headers(
                    Header.Cookie(NonEmptyChunk(Cookie.Request("deviceId", "test-device"))),
                    Header.Origin("http", "localhost")
                  )
        _ <- socket
               .connect(s"ws://127.0.0.1:$port/bridge/web-socket/missing-view", headers)
               .timeoutFail(new RuntimeException("Socket did not finish"))(Duration.fromSeconds(8))
        _ <- terminated.await.timeoutFail(new RuntimeException("Socket did not close"))(Duration.fromSeconds(1))
        received <- frames.get
      } yield received
    }.provide(Client.default, Server.defaultWith(_.onAnyOpenPort))

    val outcome = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(program) }
    outcome match {
      case Exit.Success(received) =>
        received shouldBe Vector("[1]")
        opened.get() shouldBe 0
        resumed.get() shouldBe 1
      case Exit.Failure(cause) =>
        fail(cause.prettyPrint)
    }
  }
}
