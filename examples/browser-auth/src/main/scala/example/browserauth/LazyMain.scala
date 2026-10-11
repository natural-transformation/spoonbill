package example.browserauth

import _root_.cats.effect.{ExitCode, IO, IOApp, Resource}
import _root_.cats.effect.unsafe.IORuntime
import _root_.cats.syntax.all.*
import com.typesafe.config.ConfigFactory
import java.util.concurrent.{ArrayBlockingQueue, ThreadPoolExecutor, TimeUnit}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.{ExceptionHandler, Route}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.stream.Materializer
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect
import spoonbill.pekko.{pekkoHttpService, PekkoHttpServerConfig}
import spoonbill.server.SessionAccessDenied
import spoonbill.state.javaSerialization.*

/**
 * Native lazy application: the shared UI, account policy, coordinator and
 * memory provider all execute in IO. Only Pekko's transport lifecycle APIs
 * cross a Future boundary. Resource owns startup, cancellation and release.
 */
object LazyMain extends IOApp {
  private given effect: Effect[IO] = new IO3Effect(IORuntime.global)

  private def server(port: Int): Resource[IO, MemoryAccounts[IO]] = for {
    system <- Resource.make(IO {
                val settings = ConfigFactory
                  .parseString("pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off")
                  .withFallback(ConfigFactory.load())
                ActorSystem("spoonbill-browser-auth-io", settings)
              })(system => IO.fromFuture(IO(system.terminate())).void)
    workers <- Resource.make(
                 IO(
                   new ThreadPoolExecutor(
                     8,
                     8,
                     0L,
                     TimeUnit.MILLISECONDS,
                     new ArrayBlockingQueue[Runnable](256),
                     new ThreadPoolExecutor.AbortPolicy()
                   )
                 )
               ) { workers =>
                 IO.blocking {
                   workers.shutdown()
                   if (!workers.awaitTermination(30, TimeUnit.SECONDS))
                     throw new IllegalStateException("Example workers did not settle after browser release")
                 }
               }
    accounts <- Resource.make(
                  IO.blocking(
                    new MemoryAccounts[IO](
                      origin = s"http://localhost:$port",
                      proofContext = ExecutionContext.fromExecutor(workers)
                    )(using effect, system.dispatcher)
                  )
                )(_.close())
    _ <- Resource.eval(accounts.initialize())
    _ <- Resource.make(IO.defer {
           given ActorSystem      = system
           given ExecutionContext = system.dispatcher
           given Materializer     = Materializer(system)
           val application        = new Application[IO](accounts)
           val service =
             pekkoHttpService(application.config).apply(PekkoHttpServerConfig(maxRequestBodySize = 64 * 1024))
           val denied = ExceptionHandler { case _: SessionAccessDenied =>
             complete(
               HttpResponse(
                 StatusCodes.Forbidden,
                 headers = List(RawHeader("Cache-Control", "no-store")),
                 entity = HttpEntity(
                   ContentTypes.`text/html(UTF-8)`,
                   "<h1>Authentication required</h1><a href=\"/\">Public page</a>" +
                     "<form method=\"post\" action=\"/auth/logout\"><button>Sign out and start again</button></form>"
                 )
               )
             )
           }
           val route: Route = handleExceptions(denied) {
             path("health")(get(complete("ready"))) ~
               path("no-http-proof-submission") {
                 extractRequest { request =>
                   request.discardEntityBytes(); complete(StatusCodes.NotFound)
                 }
               } ~
               service
           }
           IO.fromFuture(IO(Http().newServerAt("127.0.0.1", port).bindFlow(route)))
         })(binding => IO.fromFuture(IO(binding.unbind())).void *> IO.fromFuture(IO(binding.terminate(5.seconds))).void)
  } yield accounts

  def run(args: List[String]): IO[ExitCode] = IO {
    require(args.size <= 1, "Use [port]")
    val port = args.headOption.fold(8080)(_.toInt)
    require(port > 0 && port <= 65535, "Port must be between 1 and 65535")
    port
  }.flatMap { port =>
    server(port).use { accounts =>
      IO.println(s"Synthetic native IO browser-auth example ready at http://localhost:$port") *>
        (IO.sleep(1.second) *> accounts.browser.maintenance()).foreverM
    }.as(ExitCode.Success)
  }
}
