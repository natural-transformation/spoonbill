package spoonbill.server

import avocet.RendererCollisionWitnesses
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Router
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.effect.io.ServerSocket
import spoonbill.state.{StateManager, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Synthetic, loopback-only Spoonbill service used by
 * `scripts/test-renderer-acceptance.sh` (follow-up F2 of Avocet 2.0.0).
 *
 * Each run is a fresh, unguarded `spoonbillService` whose page is rendered by
 * the real HTML5 SSR path. The browser keeps that parsed page as its baseline,
 * so the first event-driven patch addresses parsed nodes directly. Output is
 * passed through unchanged, except in the explicit `truncate-names` fault
 * mode, which shortens long names in DOM patches to the former Avocet 1.x
 * header limits to show that the browser assertions detect that defect.
 */
object RendererAcceptanceBrowserFixture {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private val idModes                         = Map("plain" -> false, "preset" -> true)
  private val faults                          = Set("none", "truncate-names")
  private val silentReporter = new Reporter {
    def debug(message: String): Unit                         = ()
    def debug(message: String, a: Any): Unit                 = ()
    def debug(message: String, a: Any, b: Any): Unit         = ()
    def debug(message: String, a: Any, b: Any, c: Any): Unit = ()
    def info(message: String): Unit                          = ()
    def warning(message: String): Unit                       = ()
    def warning(message: String, cause: Throwable): Unit     = ()
    def error(message: String): Unit                         = ()
    def error(message: String, cause: Throwable): Unit       = ()
  }

  /** Shortens each long name in a DOM patch frame, as the removed fixed-size headers did. */
  private def truncateNames(bytes: Bytes): Bytes = {
    val text = bytes.asUtf8String
    if (!text.startsWith("[4,")) bytes
    else {
      val shortened = RendererAcceptanceDocuments.Names.truncatable.foldLeft(text) { case (frame, (name, limit)) =>
        frame.replace(s""""$name"""", s""""${name.take(limit)}"""")
      }
      Bytes.wrap(shortened.getBytes(StandardCharsets.UTF_8))
    }
  }

  /**
   * The default storage, and the sessions that a control made it forget: a
   * page whose stored state is gone before it connects. As with a state that
   * was removed, a later `create` of that session stores a new one.
   */
  final class ForgettingStorage extends StateStorage[Future, Int] {
    private val underlying                                  = StateStorage[Future, Int]()
    private val forgotten                                   = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    override private[spoonbill] def localToProcess: Boolean = true
    def forget(session: String): Unit                       = { forgotten.add(session); () }
    def exists(device: String, session: String): Future[Boolean] =
      if (forgotten.contains(session)) Future.successful(false) else underlying.exists(device, session)
    def get(device: String, session: String): Future[StateManager[Future]] =
      if (forgotten.contains(session)) Future.failed(new NoSuchElementException("Forgotten by the fixture"))
      else underlying.get(device, session)
    def create(device: String, session: String, state: Int): Future[StateManager[Future]] = {
      forgotten.remove(session)
      underlying.create(device, session, state)
    }
    def remove(device: String, session: String): Unit = underlying.remove(device, session)
  }

  /**
   * A hold on the route changes of a run (the `hold-route` and `release-route`
   * controls). While it is held, the state of a route waits for the release:
   * the server has taken the history event, but has neither rendered the new
   * route nor published its handlers. `waiting` counts the route changes that
   * wait for the release.
   */
  final class RouteHold {
    private val gate    = new AtomicReference[Option[Promise[Unit]]](None)
    private val counter = new AtomicInteger(0)
    def hold(): Unit    = { gate.compareAndSet(None, Some(Promise[Unit]())); () }
    def release(): Unit = gate.getAndSet(None).foreach(_.trySuccess(()))
    def waiting: Int    = counter.get()
    def await(implicit ec: ExecutionContext): Future[Unit] = gate.get() match {
      case None => Future.unit
      case Some(released) =>
        counter.incrementAndGet()
        released.future.andThen { case _ => counter.decrementAndGet() }
    }
  }

  /**
   * The route of the `resume` scenario: `/orders/<stage>`. An address can also
   * name the stage in its query, `/orders?stage=<stage>`, and bare `/orders`
   * is stage 0: a first address whose query matters.
   */
  private val orders = "/orders/([0-9]{1,6})".r
  private def resumeRouter(hold: RouteHold)(implicit ec: ExecutionContext) = Router[Future, Int](
    { case stage => PathAndQuery.fromString(s"/orders/$stage") },
    {
      case path if orders.matches(path.mkString) => _ => hold.await.map(_ => path.mkString.stripPrefix("/orders/").toInt)
      case path if path.asPath.mkString == "/orders" =>
        _ => hold.await.map(_ => path.param("stage").flatMap(_.toIntOption).getOrElse(0))
    }
  )

  /**
   * The route of the `resume-unicode` scenario: `/café/<stage>`. The server
   * writes the URL as it is, and the browser encodes it, so the route takes
   * the path in either form.
   */
  private val cafe = "/café/([0-9]{1,6})".r
  private def unicodeRouter(hold: RouteHold)(implicit ec: ExecutionContext) = Router[Future, Int](
    { case stage => PathAndQuery.fromString(s"/café/$stage") },
    {
      case path if cafe.matches(URLDecoder.decode(path.mkString, StandardCharsets.UTF_8)) =>
        _ => hold.await.map(_ => URLDecoder.decode(path.mkString, StandardCharsets.UTF_8).stripPrefix("/café/").toInt)
    }
  )

  /** The pathname stays /café while the stage changes in the query. */
  private def unicodeQueryRouter(hold: RouteHold)(implicit ec: ExecutionContext) = Router[Future, Int](
    { case stage => PathAndQuery.fromString("/café").withParam("stage", stage.toString) },
    { case path if URLDecoder.decode(path.asPath.mkString, StandardCharsets.UTF_8) == "/café" =>
      _ => hold.await.map(_ => path.param("stage").flatMap(_.toIntOption).getOrElse(0))
    }
  )

  final class Run(val scenario: String, val presetIds: Boolean, val fault: String)(implicit ec: ExecutionContext) {
    val counters  = new RendererAcceptanceDocuments.Counters
    val storage   = new ForgettingStorage
    val routeHold = new RouteHold
    private val unicode = scenario == "resume-unicode" || scenario == "resume-unicode-query"
    private val resume  = scenario == "resume" || scenario == "resume-heartbeat" || scenario == "resume-long" || unicode
    val service = spoonbillService(
      SpoonbillServiceConfig[Future, Int, Any](
        stateLoader = StateLoader.default(0),
        document = RendererAcceptanceDocuments.document(scenario, counters),
        router =
          if (scenario == "resume-unicode-query") unicodeQueryRouter(routeHold)
          else if (scenario == "resume-unicode") unicodeRouter(routeHold)
          else if (resume) resumeRouter(routeHold)
          else Router.empty[Future, Int],
        presetIds = presetIds,
        // `resume-heartbeat` runs the heartbeat of long polling: a tick every
        // 500 ms and the default limit of two.
        heartbeatInterval = if (scenario == "resume-heartbeat") 500.millis else 1.minute,
        // A short idle timeout lets a case end a view by blocking its polls.
        // A poll waits half of it, and a navigation the deadline of a poll:
        // 4.5 s. `resume-long` gives a navigation 20 s, for the cases that hold
        // one on purpose, so that a slow run does not reach the bound, and so
        // does `resume-unicode`, the same page at `/café/<stage>`.
        sessionIdleTimeout =
          if (scenario == "resume-long" || unicode) 30.seconds
          else if (resume) 6.seconds
          else 60.seconds,
        stateStorage = storage,
        reporter = silentReporter
      )
    )
    def output(source: Stream[Future, Bytes]): Stream[Future, Bytes] =
      if (fault == "truncate-names") source.map(truncateNames) else source
    def metadata: String =
      s"""{"scenario":"$scenario","presetIds":$presetIds,"fault":"$fault","witnessesCollide":true,${counters.json},"routeWaits":${routeHold.waiting},"names":${RendererAcceptanceDocuments.Names.json}}"""
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Pass the readiness file path")
    // Refuse to start when a witness no longer collides in the resolved avocet-core JAR.
    RendererCollisionWitnesses.requireStillCollide()
    val executor                      = Executors.newFixedThreadPool(6)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)
    val group                         = AsynchronousChannelGroup.withFixedThreadPool(4, Executors.defaultThreadFactory())
    val current = new AtomicReference[Option[Run]](None)
    def active: Run = current.get().getOrElse(throw new IllegalStateException("Start a run first"))
    val service = new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] = {
        val path = request.pq.asPath.mkString
        if (path == "/health") HttpResponse.ok[Future]("ready")
        else if (path.startsWith("/fixture/start/")) {
          // /fixture/start/<scenario>/<plain|preset>/<none|truncate-names>
          path.stripPrefix("/fixture/start/").split('/').toList match {
            case scenario :: ids :: fault :: Nil
                if RendererAcceptanceDocuments.scenarios(scenario) && idModes.contains(ids) && faults(fault) &&
                  request.method == Request.Method.Post =>
              val run = new Run(scenario, idModes(ids), fault)
              current.set(Some(run))
              HttpResponse.ok[Future](run.metadata)
            case _ => Future.failed(new IllegalArgumentException("Unknown fixture run"))
          }
        } else if (path == "/fixture/status") HttpResponse.ok[Future](active.metadata)
        else if (path.startsWith("/fixture/forget/") && request.method == Request.Method.Post) {
          // The stored state of this session is gone, as after a removal.
          active.storage.forget(path.stripPrefix("/fixture/forget/"))
          HttpResponse.ok[Future]("forgotten")
        } else if (path == "/fixture/hold-route" && request.method == Request.Method.Post) {
          // Route changes wait from now on, until the release.
          active.routeHold.hold()
          HttpResponse.ok[Future]("held")
        } else if (path == "/fixture/release-route" && request.method == Request.Method.Post) {
          active.routeHold.release()
          HttpResponse.ok[Future]("released")
        } else active.service.http(request)
      }
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = {
        val run = active
        run.service.ws(request).map(_.mapOutput(run.output))
      }
    }
    val server  = new AtomicReference[Option[ServerSocket.ServerSocketHandler[Future]]](None)
    val stopped = new AtomicBoolean()
    def cleanup(): Unit = if (stopped.compareAndSet(false, true)) {
      try Await.result(server.get().fold(Future.unit)(_.stopServingRequests()), 10.seconds)
      finally { group.shutdownNow(); executor.shutdownNow() }
    }
    Runtime.getRuntime.addShutdownHook(new Thread(() => cleanup(), "renderer-fixture-cleanup"))
    try {
      val handler = Await.result(
        standalone.buildServer[Future, Array[Byte]](
          service,
          new InetSocketAddress("127.0.0.1", 0),
          group,
          gracefulShutdown = false
        ),
        15.seconds
      )
      server.set(Some(handler))
      val port = handler.localAddress.asInstanceOf[InetSocketAddress].getPort
      Files.writeString(Path.of(args(0)), s"http://127.0.0.1:$port")
      Await.result(Promise[Unit]().future, Duration.Inf)
    } finally cleanup()
  }
}
