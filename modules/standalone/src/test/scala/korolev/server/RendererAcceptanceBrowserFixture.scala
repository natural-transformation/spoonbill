package spoonbill.server

import avocet.RendererCollisionWitnesses
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.effect.io.ServerSocket
import spoonbill.state.javaSerialization.*
import spoonbill.web.Request

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

  final class Run(val scenario: String, val presetIds: Boolean, val fault: String)(implicit ec: ExecutionContext) {
    val counters = new RendererAcceptanceDocuments.Counters
    val service = spoonbillService(
      SpoonbillServiceConfig[Future, Int, Any](
        stateLoader = StateLoader.default(0),
        document = RendererAcceptanceDocuments.document(scenario, counters),
        presetIds = presetIds,
        heartbeatInterval = 1.minute,
        reporter = silentReporter
      )
    )
    def output(source: Stream[Future, Bytes]): Stream[Future, Bytes] =
      if (fault == "truncate-names") source.map(truncateNames) else source
    def metadata: String =
      s"""{"scenario":"$scenario","presetIds":$presetIds,"fault":"$fault","witnessesCollide":true,${counters.json},"names":${RendererAcceptanceDocuments.Names.json}}"""
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
        else active.service.http(request)
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
