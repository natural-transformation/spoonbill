package spoonbill.server

import java.net.InetSocketAddress
import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CompletableFuture, CopyOnWriteArrayList, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{AuthenticationCompletionConfig, SessionAccessControl, SessionGuard, SpoonbillServiceConfig, StateLoader}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*
import spoonbill.web.Request

/** Delivery and closure at the standalone socket, not only in a core stream. */
final class StandaloneMissingViewReloadSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val timeout = 10.seconds

  private final class NoResumeControl extends SessionAccessControl[Future, String] {
    val opened = new AtomicInteger(0)
    val resumed = new AtomicInteger(0)
    def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
    def open(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): Future[SessionGuard[Future, String]] = {
      opened.incrementAndGet()
      Future.failed(new IllegalStateException("An absent local view must not open a guard"))
    }
    override def resume(
      qsid: Qsid,
      request: Request.Head,
      connectionId: ConnectionId
    ): Option[Future[SessionGuard[Future, String]]] = {
      resumed.incrementAndGet()
      None
    }
  }

  private def expectReload(control: Option[NoResumeControl]): Unit = {
    val rebuilds = new AtomicInteger(0)
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader[Future, String] { (_, _) =>
        rebuilds.incrementAndGet()
        Future.failed(new IllegalStateException("Synthetic view cannot be rebuilt"))
      },
      stateStorage = StateStorage.ephemeral[Future, String](16),
      sessionAccessControl = control,
      authenticationCompletion = control.map(_ => AuthenticationCompletionConfig[Future](
        bindingCookieName = "test-binding",
        sessionCookieName = "test-session",
        allowedOrigins = Set("http://localhost"),
        secureCookies = false,
        cookieMaxAgeSeconds = 60L,
        deliver = (_, _) => Future.successful(None),
        logout = (_, _) => Future.unit
      ))
    )
    val handler = Await.result(
      standalone.buildServer[Future, Array[Byte]](
        spoonbillService(config),
        new InetSocketAddress("127.0.0.1", 0),
        null,
        gracefulShutdown = false
      ),
      timeout
    )
    val port = handler.localAddress.asInstanceOf[InetSocketAddress].getPort
    val frames = new CopyOnWriteArrayList[String]()
    val done = new CompletableFuture[Unit]()
    val listener = new WebSocket.Listener {
      override def onOpen(webSocket: WebSocket): Unit =
        webSocket.request(1)
      override def onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): java.util.concurrent.CompletionStage[?] = {
        val bytes = new Array[Byte](data.remaining())
        data.get(bytes)
        frames.add(new String(bytes, StandardCharsets.UTF_8))
        webSocket.request(1)
        null
      }
      override def onClose(webSocket: WebSocket, statusCode: Int, reason: String): java.util.concurrent.CompletionStage[?] = {
        done.complete(())
        null
      }
      override def onError(webSocket: WebSocket, error: Throwable): Unit =
        done.completeExceptionally(error)
    }
    val client = HttpClient.newHttpClient()
    try {
      client
        .newWebSocketBuilder()
        .header("Cookie", "deviceId=test-device")
        .header("Origin", "http://localhost")
        .subprotocols("json")
        .buildAsync(URI.create(s"ws://127.0.0.1:$port/bridge/web-socket/missing-view"), listener)
        .join()
      done.get(8, TimeUnit.SECONDS)
      frames.asScala.toList shouldBe List("[1]")
      rebuilds.get() shouldBe (if (control.isEmpty) 1 else 0)
      control.foreach { probe =>
        probe.opened.get() shouldBe 0
        probe.resumed.get() shouldBe 1
      }
    } finally {
      Await.result(handler.stopServingRequests(), timeout)
      client.close()
    }
  }

  "Missing local view over the standalone socket" should "send reload and close when rebuild fails" in {
    expectReload(None)
  }

  it should "send reload and close when guarded recovery declines the view" in {
    expectReload(Some(new NoResumeControl))
  }
}
