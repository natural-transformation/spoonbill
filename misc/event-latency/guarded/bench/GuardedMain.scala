package bench

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.auth.*
import spoonbill.pekko.*
import spoonbill.security.jdbc.*
import spoonbill.server.*
import spoonbill.state.javaSerialization.*

/**
 * The counter app behind the real BrowserAuthentication coordinator.
 * Usage: GuardedMain <memory|jdbc> <port>. Anonymous views are allowed, so the
 * client needs no login, but every authorization check still runs.
 * BENCH_MAINTENANCE_MS sets the maintenance cadence (default 30000). GET
 * /bench-stats returns the coordinator's counters where the variant has them.
 * This file also compiles against the released 8.0.1, so the working-tree-only
 * opt-in lives in local-only/ConcurrentMain.
 */
object GuardedMain {
  val scope: BrowserProviderScope = BrowserProviderScope("bench", "session")

  /** The JDBC pieces every JDBC variant shares: pool, blocking executor, host and vault. */
  final case class JdbcSetup(
    source: HikariDataSource,
    blocking: ExecutionContext,
    host: JdbcHostSessions[String],
    vault: JdbcCredentialVault
  )

  def jdbcSetup(): JdbcSetup = {
    val pool = new HikariConfig()
    pool.setJdbcUrl(sys.env("SPOONBILL_JDBC_TEST_URL"))
    pool.setMaximumPoolSize(32)
    val source   = new HikariDataSource(pool)
    val blocking = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(16))
    val host = new JdbcHostSessions[String] {
      def resolve(connection: Connection, sessionId: UUID, now: Instant): Option[HostPrincipal[String]] = None
      def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit = ()
    }
    // Benchmark-only key; never use a fixed key for real deployments.
    val key = java.security.MessageDigest.getInstance("SHA-256").digest("bench-only".getBytes("UTF-8"))
    JdbcSetup(source, blocking, host, JdbcCredentialVault.aesGcm(key))
  }

  /** Run the provider's explicit migration once, as a deployment would. */
  def initialized(
    setup: JdbcSetup,
    jdbc: JdbcBrowserPersistence[Future, String]
  ): JdbcBrowserPersistence[Future, String] = {
    Using.resource(setup.source.getConnection) { connection =>
      connection.setAutoCommit(false)
      jdbc.initialize(connection)
      connection.commit()
    }
    jdbc
  }

  def main(args: Array[String]): Unit = {
    val Array(provider, portArg) = args
    serve(provider, portArg.toInt) { executor =>
      given ExecutionContext = executor
      provider match {
        case "memory" =>
          val host = new InMemoryBrowserPersistence.Host[Unit, String] {
            def resolve(state: Unit, sessionId: UUID, now: Instant): Option[HostPrincipal[String]] = None
          }
          new InMemoryBrowserPersistence[Future, Unit, String](
            (),
            host,
            () => Instant.now(),
            () => UUID.randomUUID(),
            scope = scope
          )
        case "jdbc" =>
          val setup = jdbcSetup()
          initialized(
            setup,
            new JdbcBrowserPersistence[Future, String](
              setup.source,
              setup.blocking,
              scope.realm,
              scope.cookieNamespace,
              () => Instant.now(),
              setup.host,
              setup.vault,
              BrowserOwnership.ExclusiveScope
            )
          )
      }
    }
  }

  /**
   * Serve the counter app behind the coordinator. `build` makes the provider
   * once the actor system exists, with its dispatcher; `stats` renders the
   * coordinator's counters for GET /bench-stats, where the variant has them.
   */
  def serve(label: String, port: Int)(
    build: ExecutionContext => BrowserPersistence[Future, String],
    stats: BrowserAuthentication[Future, Counter, String] => String = _ => "{}"
  ): Unit = {
    val origin                = s"http://127.0.0.1:$port"
    given system: ActorSystem = ActorSystem("bench-guarded")
    given ExecutionContext    = system.dispatcher
    given Materializer        = Materializer(system)
    val persistence = build(system.dispatcher)
    val presentation = BrowserPresentation[Counter, String](
      fresh = () => Counter(0),
      allowed = (_, _) => true,
      connected = (state, _, _) => state
    )
    val browser = new BrowserAuthentication[Future, Counter, String](
      persistence,
      presentation,
      BrowserConfiguration(scope, Set(origin), secureCookies = false)
    )
    val config = browser.configure(
      SpoonbillServiceConfig[Future, Counter, Any](
        stateLoader = StateLoader.default(Counter(0)),
        document = CounterPage.document
      )
    )
    val cadence = sys.env.get("BENCH_MAINTENANCE_MS").map(_.toLong.millis).getOrElse(30.seconds)
    system.scheduler.scheduleWithFixedDelay(cadence, cadence)(() => { browser.maintenance(); () })
    val route = concat(
      path("bench-stats")(get(complete(stats(browser)))),
      pekkoHttpService(config).apply(PekkoHttpServerConfig())
    )
    Http().newServerAt("127.0.0.1", port).bindFlow(route).foreach { _ =>
      println(s"bench-guarded-$label ready on $port rows=${CounterPage.rows} maintenanceMs=${cadence.toMillis}")
    }
  }
}
