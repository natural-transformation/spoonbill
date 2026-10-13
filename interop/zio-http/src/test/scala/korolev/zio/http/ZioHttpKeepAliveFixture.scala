package spoonbill.zio.http

import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
import spoonbill.Qsid
import spoonbill.data.Bytes
import spoonbill.effect.{Reporter, Stream}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{
  AuthenticationCompletionConfig,
  BrowserSessionToken,
  HttpRequest,
  HttpResponse,
  SessionAccessControl,
  SessionGuard,
  SpoonbillServiceConfig,
  StateLoader
}
import spoonbill.state.javaSerialization.*
import spoonbill.web.{Request as SpoonbillRequest, Response as SpoonbillResponse}
import spoonbill.web.PathAndQuery.{/, Root}
import spoonbill.zio.Zio2Effect
import zio.{Duration, RIO, Runtime, Scope, Task, ZIO}
import zio.http.{Response, Routes, Server}

/**
 * Synthetic, in-memory Spoonbill service for connection-level ZIO HTTP tests.
 * No account service, database or consumer application is involved.
 */
object ZioHttpKeepAliveFixture {
  type AppTask[A] = RIO[Any, A]

  implicit val runtime: Runtime[Any]              = Runtime.default
  implicit val ec: ExecutionContext               = Runtime.defaultExecutor.asExecutionContext
  implicit val effect: Zio2Effect[Any, Throwable] = new Zio2Effect[Any, Throwable](runtime, identity, identity)

  val Origin          = "http://localhost"
  val BindingCookie   = "test-binding"
  val SessionCookie   = "test-session"
  val Binding: String = "B" * 43
  val Token: String   = "T" * 43

  val silentReporter: Reporter = new Reporter {
    def error(message: String, cause: Throwable): Unit                = ()
    def error(message: String): Unit                                  = ()
    def warning(message: String, cause: Throwable): Unit              = ()
    def warning(message: String): Unit                                = ()
    def info(message: String): Unit                                   = ()
    def debug(message: String): Unit                                  = ()
    def debug(message: String, arg1: Any): Unit                       = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  }

  /**
   * Counts acquisition and release of a resource owned by a response stream.
   */
  final class TrackedResource extends Closeable {
    val acquired      = new AtomicInteger(0)
    val released      = new AtomicInteger(0)
    val pulls         = new AtomicInteger(0)
    def close(): Unit = { released.incrementAndGet(); () }
  }

  private val permissiveControl = new SessionAccessControl[AppTask, String] {
    def authorizeHttp(request: SpoonbillRequest.Head, state: String): AppTask[Unit] = ZIO.unit
    def open(
      qsid: Qsid,
      request: SpoonbillRequest.Head,
      connectionId: ConnectionId
    ): AppTask[SessionGuard[AppTask, String]] =
      ZIO.fail(new IllegalStateException("Transport tests do not open guarded views"))
  }

  /**
   * Each handle can be delivered once; a replay is the consumed-handle 403
   * path.
   */
  private def completion(logoutRedirect: Option[String]): AuthenticationCompletionConfig[AppTask] = {
    val consumed = ConcurrentHashMap.newKeySet[UUID]()
    AuthenticationCompletionConfig[AppTask](
      bindingCookieName = BindingCookie,
      sessionCookieName = SessionCookie,
      allowedOrigins = Set(Origin),
      secureCookies = false,
      cookieMaxAgeSeconds = 60L,
      deliver = (handle, binding) =>
        ZIO.succeed {
          if (binding == Binding && consumed.add(handle)) BrowserSessionToken.fromString(Token).toOption
          else None
        },
      logout = (_, _) => ZIO.unit,
      logoutRedirect = logoutRedirect
    )
  }

  private def bytes(text: String): Bytes = Bytes.wrap(text.getBytes(StandardCharsets.UTF_8))

  private def response(
    code: Int,
    phrase: String,
    body: Stream[AppTask, Bytes],
    length: Option[Long],
    headers: Seq[(String, String)] = Seq("X-Test" -> "kept")
  ): AppTask[HttpResponse[AppTask]] =
    ZIO.succeed(SpoonbillResponse(SpoonbillResponse.Status(code, phrase), body, headers, length))

  private def emits(values: String*): AppTask[Stream[AppTask, Bytes]] =
    Stream(values.map(bytes)*).mat[AppTask]()

  /**
   * A zero-length stream that owns a resource acquired before the adapter sees
   * it.
   */
  def resourceStream(resource: TrackedResource): AppTask[Stream[AppTask, Bytes]] =
    Stream.unfoldResource[AppTask, TrackedResource, Unit, Bytes](
      ZIO.succeed { resource.acquired.incrementAndGet(); resource },
      (),
      (_, state) => ZIO.succeed { resource.pulls.incrementAndGet(); (state, None) }
    )

  /** Ordinary HTTP handlers reached through `SpoonbillServiceConfig.http`. */
  def handlers(resource: TrackedResource): PartialFunction[HttpRequest[AppTask], AppTask[HttpResponse[AppTask]]] = {
    case request if request.pq.asPath == Root / "health" =>
      request.body.cancel() *> HttpResponse.ok[AppTask]("ok")
    case request if request.pq.asPath == Root / "empty" / "200" / "stream-empty" =>
      response(200, "OK", Stream.empty[AppTask, Bytes], Some(0L))
    case request if request.pq.asPath == Root / "empty" / "200" / "empty-bytes" =>
      emits("").flatMap(response(200, "OK", _, Some(0L)))
    case request if request.pq.asPath == Root / "empty" / "410" / "stream-empty" =>
      response(410, "Gone", Stream.empty[AppTask, Bytes], Some(0L))
    case request if request.pq.asPath == Root / "empty" / "410" / "empty-bytes" =>
      HttpResponse[AppTask](SpoonbillResponse.Status(410, "Gone"), "", Seq("X-Test" -> "kept"))
    case request if request.pq.asPath == Root / "known" =>
      emits("alpha-", "", "beta-", "gamma").flatMap(response(200, "OK", _, Some(16L)))
    case request if request.pq.asPath == Root / "chunked" =>
      emits("one-", "two-", "three").flatMap(response(200, "OK", _, None))
    case request if request.pq.asPath == Root / "chunked-empty" =>
      response(200, "OK", Stream.empty[AppTask, Bytes], None)
    case request if request.pq.asPath == Root / "resource" =>
      resourceStream(resource).flatMap(response(200, "OK", _, Some(0L)))
    case request if request.pq.asPath == Root / "redirect" =>
      response(
        303,
        "See Other",
        Stream.empty[AppTask, Bytes],
        Some(0L),
        Seq("Location" -> "/health", "Set-Cookie" -> "custom=1; Path=/", "Cache-Control" -> "no-store")
      )
  }

  def config(
    resource: TrackedResource = new TrackedResource,
    logoutRedirect: Option[String] = Some("/"),
    http: Option[PartialFunction[HttpRequest[AppTask], AppTask[HttpResponse[AppTask]]]] = None
  ): SpoonbillServiceConfig[AppTask, String, Any] =
    SpoonbillServiceConfig[AppTask, String, Any](
      stateLoader = StateLoader.default("initial"),
      http = http.getOrElse(handlers(resource)),
      reporter = silentReporter,
      webSocketProtocolsEnabled = false,
      sessionAccessControl = Some(permissiveControl),
      authenticationCompletion = Some(completion(logoutRedirect))
    )

  def routes(config: SpoonbillServiceConfig[AppTask, String, Any]): Routes[Any, Response] =
    new ZioHttpSpoonbill[Any].service(config)

  /**
   * Run `use` against a loopback server on an OS-assigned port, with a bounded
   * overall duration.
   */
  def withServer[A](routes: Routes[Any, Response], limit: Duration = Duration.fromSeconds(20))(
    use: Int => ZIO[Scope, Throwable, A]
  ): Task[A] =
    ZIO
      .scoped(Server.install(routes).flatMap(use))
      .provide(Server.defaultWith(_.binding("127.0.0.1", 0)))
      .timeoutFail(new java.util.concurrent.TimeoutException(s"Scenario exceeded $limit"))(limit)

  def authHeaders(extra: (String, String)*): Seq[(String, String)] =
    Seq("Origin" -> Origin, "Cookie" -> s"$BindingCookie=$Binding") ++ extra
}
