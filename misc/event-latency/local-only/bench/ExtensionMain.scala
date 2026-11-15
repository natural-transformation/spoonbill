package bench

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.Extension
import spoonbill.auth.*
import spoonbill.pekko.*
import spoonbill.server.*
import spoonbill.state.javaSerialization.*

/**
 * The counter app with extensions that observe every state change. Usage:
 * ExtensionMain <plain|memory> <extensions> <port>. `plain` is the unguarded
 * app; `memory` puts it behind the coordinator with the in-memory provider. GET
 * /bench-stats reports how many states the extensions received, so a run shows
 * that delivery happened. Extensions use the working tree's API, so this main
 * lives outside the shared sources.
 */
object ExtensionMain {
  def main(args: Array[String]): Unit = {
    val Array(mode, countArg, portArg) = args
    val port                           = portArg.toInt
    given system: ActorSystem          = ActorSystem("bench-extensions")
    given ExecutionContext             = system.dispatcher
    given Materializer                 = Materializer(system)
    val delivered                      = new AtomicLong(0)
    val extensions = List.tabulate(countArg.toInt) { index =>
      Extension.pure[Future, Counter, Any](s"bench-$index") { _ =>
        Extension.Handlers[Future, Counter, Any](onState = _ => { delivered.incrementAndGet(); Future.unit })
      }
    }
    val base = SpoonbillServiceConfig[Future, Counter, Any](
      stateLoader = StateLoader.default(Counter(0)),
      document = CounterPage.document,
      extensions = extensions
    )
    val config = mode match {
      case "plain" => base
      case "memory" =>
        val host = new InMemoryBrowserPersistence.Host[Unit, String] {
          def resolve(state: Unit, sessionId: UUID, now: Instant): Option[HostPrincipal[String]] = None
        }
        val persistence = new InMemoryBrowserPersistence[Future, Unit, String](
          (),
          host,
          () => Instant.now(),
          () => UUID.randomUUID(),
          scope = GuardedMain.scope
        )
        val presentation = BrowserPresentation[Counter, String](
          fresh = () => Counter(0),
          allowed = (_, _) => true,
          connected = (state, _, _) => state
        )
        new BrowserAuthentication[Future, Counter, String](
          persistence,
          presentation,
          BrowserConfiguration(GuardedMain.scope, Set(s"http://127.0.0.1:$port"), secureCookies = false)
        ).configure(base)
    }
    val route = concat(
      path("bench-stats")(get(complete(s"""{"statesDelivered":${delivered.get()}}"""))),
      pekkoHttpService(config).apply(PekkoHttpServerConfig())
    )
    Http().newServerAt("127.0.0.1", port).bindFlow(route).foreach { _ =>
      println(s"bench-extensions-$mode ready on $port extensions=${extensions.size} rows=${CounterPage.rows}")
    }
  }
}
