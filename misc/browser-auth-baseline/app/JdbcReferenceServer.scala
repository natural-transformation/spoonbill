package spoonbill.browserauthbaseline

import com.typesafe.config.ConfigFactory
import java.io.PrintWriter
import java.net.URI
import java.security.SecureRandom
import java.sql.{Connection, DriverManager, SQLFeatureNotSupportedException}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.{Executors, ExecutorService, Semaphore}
import java.util.logging.Logger
import javax.sql.DataSource
import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorSystem, CoordinatedShutdown}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Try, Using}
import spoonbill.security.jdbc.baseline.JdbcReferenceHost

/**
 * Disposable loopback PostgreSQL reference only. The public synthetic key and
 * seed accounts make this intentionally unsuitable for real user data. The
 * shared official route contains all HTTP/WS adaptation; no transport copy.
 */
object JdbcReferenceServer {
  private[browserauthbaseline] def releaseWorkersAfter(
    settlement: Future[Unit],
    workers: ExecutorService
  ): Future[Done] = settlement.transform { result =>
    // Settlement can arrive after ActorSystem termination. The finalizer must
    // remain runnable without that system's dispatcher and never interrupt SQL.
    workers.shutdown()
    result.map(_ => Done)
  }(ExecutionContext.parasitic)

  private final class Source(url: String, schema: String, user: Option[String], password: Option[String])
      extends DataSource {
    private def properties: Properties = {
      val result = new Properties()
      user.foreach(result.setProperty("user", _))
      password.foreach(result.setProperty("password", _))
      result.setProperty("currentSchema", schema)
      result.setProperty("options", "-c statement_timeout=15000 -c lock_timeout=12000")
      result.setProperty("connectTimeout", "5")
      result.setProperty("socketTimeout", "20")
      result.setProperty("cancelSignalTimeout", "5")
      result
    }
    def getConnection: Connection                                 = DriverManager.getConnection(url, properties)
    def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
    def getLogWriter: PrintWriter                                 = throw new SQLFeatureNotSupportedException()
    def setLogWriter(writer: PrintWriter): Unit                   = throw new SQLFeatureNotSupportedException()
    def getLoginTimeout: Int                                      = 0
    def setLoginTimeout(seconds: Int): Unit                       = throw new SQLFeatureNotSupportedException()
    def getParentLogger: Logger                                   = Logger.getLogger("spoonbill.synthetic.jdbc.reference")
    def isWrapperFor(kind: Class[?]): Boolean                     = false
    def unwrap[T](kind: Class[T]): T                              = throw new SQLFeatureNotSupportedException()
  }

  def main(args: Array[String]): Unit = {
    val positional = args.filterNot(Set("--initialize", "--pause-after-preparation").contains)
    require(positional.length <= 1, "Use [port] [--initialize] [--pause-after-preparation]")
    val port = positional.headOption.fold(8080)(_.toInt)
    require(port > 0 && port <= 65535, "Port must be between 1 and 65535")
    val url = sys.env.getOrElse(
      "SPOONBILL_JDBC_TEST_URL",
      throw new IllegalArgumentException("Use the disposable scripts/with-test-postgres.sh database")
    )
    require(
      url.startsWith("jdbc:postgresql://") &&
        Try(URI.create(url.stripPrefix("jdbc:"))).toOption.exists(uri =>
          Set("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost)
        ),
      "The synthetic reference requires a loopback PostgreSQL database"
    )
    val schema =
      sys.env.getOrElse("SPOONBILL_BASELINE_SCHEMA", "baseline_" + UUID.randomUUID().toString.replace("-", ""))
    require(schema.matches("[a-z][a-z0-9_]{0,62}"), "Invalid synthetic schema identifier")
    val initializeSetting = sys.env.getOrElse("SPOONBILL_BASELINE_INITIALIZE", "false")
    require(Set("true", "false").contains(initializeSetting), "Initialization must be true or false")
    val initialize = args.contains("--initialize") || initializeSetting == "true"
    val source =
      new Source(url, schema, sys.env.get("SPOONBILL_JDBC_TEST_USER"), sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD"))
    val random                          = new SecureRandom()
    def entropy(size: Int): Array[Byte] = { val value = new Array[Byte](size); random.nextBytes(value); value }
    // Stable public TEST KEY permits process-restart recovery in the disposable
    // fixture. It is deliberately not a secret or an infrastructure key policy.
    val material = new JdbcReferenceHost.MaterialCipher(
      JdbcReferenceHost.syntheticHash("public-synthetic-spoonbill-baseline-key-v1"),
      () => entropy(12)
    )
    val shutdownConfig = ConfigFactory
      .parseString(
        "pekko.coordinated-shutdown.phases.before-actor-system-terminate.timeout = 10 minutes"
      )
      .withFallback(ConfigFactory.load())
    given ActorSystem      = ActorSystem("spoonbill-jdbc-browser-reference", shutdownConfig)
    given ExecutionContext = summon[ActorSystem].dispatcher
    given Materializer     = Materializer(summon[ActorSystem])
    val workers            = Executors.newFixedThreadPool(4)
    val blockingContext    = ExecutionContext.fromExecutor(workers)
    val origin             = s"http://localhost:$port"
    val backend = new JdbcReferenceBackend(
      source,
      blockingContext,
      origin,
      () => Instant.now(),
      entropy,
      () => UUID.randomUUID(),
      material,
      afterPreparedCommit = attempt =>
        if (args.contains("--pause-after-preparation")) {
          // Deterministic crash-test checkpoint: no transaction or monitor is
          // held. The owning harness kills this process, then restarts its schema.
          println(s"SYNTHETIC_PREPARATION_COMMITTED:$attempt")
          System.out.flush()
          new Semaphore(0).acquire()
        }
    )
    val app      = new JdbcReferenceApplication(backend, origin)
    val shutdown = CoordinatedShutdown(summon[ActorSystem])
    shutdown.addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "release-jdbc-reference") { () =>
      releaseWorkersAfter(app.close(), workers)
    }
    val ready = Future {
      Using.resource(source.getConnection) { connection =>
        if (initialize) {
          connection.setAutoCommit(false)
          try {
            // The identifier is strictly validated above and still quoted.
            Using.resource(connection.createStatement())(_.executeUpdate(s"CREATE SCHEMA \"$schema\""))
            backend.initialize(connection)
            connection.commit()
          } catch { case error: Throwable => connection.rollback(); throw error }
        } else
          Using.resource(connection.createStatement()) { query =>
            Using.resource(query.executeQuery("SELECT id FROM baseline_account LIMIT 1")) { rows =>
              require(rows.next(), "The selected synthetic schema is not initialized")
            }
          }
      }
    }(blockingContext)
    ready.flatMap { _ =>
      MemoryReferenceServer.maintain(app)
      Http().newServerAt("127.0.0.1", port).bindFlow(MemoryReferenceServer.route(app))
    }.onComplete {
      case scala.util.Success(binding) =>
        shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "unbind-jdbc-reference")(() =>
          binding.unbind().map(_ => Done)
        )
        shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "drain-jdbc-reference")(() =>
          binding.terminate(5.seconds).map(_ => Done)
        )
        println(s"Synthetic JDBC browser reference: http://localhost:$port (schema=$schema)")
      case scala.util.Failure(_) =>
        System.err.println(
          "The synthetic JDBC reference could not initialize or bind; use an explicit disposable schema and --initialize for its first start."
        )
        shutdown.run(CoordinatedShutdown.UnknownReason)
    }
  }
}
