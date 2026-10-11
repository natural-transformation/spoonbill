package example.browserauth

import com.typesafe.config.ConfigFactory
import java.io.PrintWriter
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.{Connection, DriverManager, SQLFeatureNotSupportedException}
import java.util.{Properties, UUID}
import java.util.concurrent.{ArrayBlockingQueue, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorSystem, CoordinatedShutdown}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.{ExceptionHandler, Route}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.stream.Materializer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try, Using}
import spoonbill.pekko.{pekkoHttpService, PekkoHttpServerConfig}
import spoonbill.security.jdbc.JdbcCredentialVault
import spoonbill.server.SessionAccessDenied
import spoonbill.state.javaSerialization.*

/**
 * Runnable, loopback-only synthetic example. The public test accounts and vault
 * key are deliberately unsuitable for real users or production data.
 */
object Main {

  /**
   * Independent connections permit the durable clock to acquire its extra
   * connection while a host transaction is open. A production pool needs a
   * reserved clock connection or at least N+1 capacity for N transactions.
   */
  private final class Source(url: String, schema: String) extends DataSource {
    def getConnection: Connection = {
      val properties = new Properties()
      sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
      sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
      properties.setProperty("currentSchema", schema)
      properties.setProperty("options", "-c statement_timeout=15000 -c lock_timeout=12000")
      properties.setProperty("connectTimeout", "5")
      properties.setProperty("socketTimeout", "20")
      properties.setProperty("cancelSignalTimeout", "5")
      DriverManager.getConnection(url, properties)
    }
    def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
    def getLogWriter: PrintWriter                                 = throw new SQLFeatureNotSupportedException()
    def setLogWriter(writer: PrintWriter): Unit                   = throw new SQLFeatureNotSupportedException()
    def getLoginTimeout: Int                                      = 5
    def setLoginTimeout(seconds: Int): Unit                       = throw new SQLFeatureNotSupportedException()
    def getParentLogger: Logger                                   = Logger.getLogger("spoonbill.synthetic.browserauth")
    def isWrapperFor(kind: Class[?]): Boolean                     = false
    def unwrap[T](kind: Class[T]): T                              = throw new SQLFeatureNotSupportedException()
  }

  def main(args: Array[String]): Unit = {
    val positional = args.filterNot(_ == "--initialize")
    require(positional.length <= 1, "Use [port] [--initialize]")
    val port = positional.headOption.fold(8080)(_.toInt)
    require(port > 0 && port <= 65535, "Port must be between 1 and 65535")
    val provider = sys.env.getOrElse("SPOONBILL_AUTH_PROVIDER", "memory")
    require(Set("memory", "jdbc").contains(provider), "SPOONBILL_AUTH_PROVIDER must be memory or jdbc")
    val origin = s"http://localhost:$port"
    val source = Option.when(provider == "jdbc") {
      val url = sys.env.getOrElse(
        "SPOONBILL_JDBC_TEST_URL",
        throw new IllegalArgumentException("Use scripts/with-test-postgres.sh for a disposable PostgreSQL database")
      )
      require(
        url.startsWith("jdbc:postgresql://") && Try(URI.create(url.stripPrefix("jdbc:"))).toOption
          .exists(uri => Set("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost)),
        "The synthetic example requires loopback PostgreSQL"
      )
      val schema = sys.env.getOrElse("SPOONBILL_AUTH_SCHEMA", "example_" + UUID.randomUUID().toString.replace("-", ""))
      require(schema.matches("[a-z][a-z0-9_]{0,62}"), "Invalid synthetic schema identifier")
      new Source(url, schema) -> schema
    }
    val settings = ConfigFactory
      .parseString(
        "pekko.coordinated-shutdown.phases.before-actor-system-terminate.timeout = 60 seconds"
      )
      .withFallback(ConfigFactory.load())
    given ActorSystem      = ActorSystem("spoonbill-browser-auth-example", settings)
    given ExecutionContext = summon[ActorSystem].dispatcher
    given Materializer     = Materializer(summon[ActorSystem])
    val workers = new ThreadPoolExecutor(
      8,
      8,
      0L,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue[Runnable](256),
      new ThreadPoolExecutor.AbortPolicy()
    )
    val workerContext = ExecutionContext.fromExecutor(workers)
    val shutdown      = CoordinatedShutdown(summon[ActorSystem])
    val owned         = new AtomicReference[Option[Accounts[Future]]](None)
    val initialized   = Promise[Unit]()
    shutdown.addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "close-browser-auth-example") { () =>
      val settlement = initialized.future
        .transform(_ => Success(()))(ExecutionContext.parasitic)
        .flatMap(_ => owned.get().fold(Future.unit)(_.close()))(ExecutionContext.parasitic)
      settlement.transform { result =>
        // Never interrupt a settling host transaction or depend on a terminated
        // actor dispatcher to release the worker executor.
        workers.shutdown()
        result.map(_ => Done)
      }(ExecutionContext.parasitic)
    }
    def startupFailed(): Unit = {
      System.err.println(
        "The synthetic browser-auth example could not initialize or bind. Check its disposable database and explicit --initialize setting."
      )
      shutdown.run(CoordinatedShutdown.UnknownReason).onComplete(_ => sys.exit(1))(ExecutionContext.parasitic)
    }
    try {
      val accounts: Accounts[Future] = source match {
        case None => new MemoryAccounts[Future](origin = origin, proofContext = workerContext)
        case Some((database, _)) =>
          val publicTestKey =
            MessageDigest.getInstance("SHA-256").digest("public-synthetic-spoonbill-example-key-v1".getBytes(UTF_8))
          new JdbcAccounts(database, workerContext, origin, JdbcCredentialVault.aesGcm(publicTestKey))
      }
      owned.set(Some(accounts))
      val application = new Application[Future](accounts)
      val initialize = source match {
        case Some((database, schema)) if args.contains("--initialize") =>
          Future(Using.resource(database.getConnection) { connection =>
            Using.resource(connection.createStatement())(_.executeUpdate(s"CREATE SCHEMA \"$schema\""))
          })(workerContext).flatMap(_ => accounts.initialize())
        case Some((database, _)) =>
          Future {
            Using.resource(database.getConnection) { connection =>
              Using.resource(connection.createStatement()) { query =>
                Using.resource(query.executeQuery("SELECT id FROM example_account LIMIT 1"))(rows =>
                  require(rows.next(), "Initialize this disposable example schema explicitly")
                )
              }
            }
          }(workerContext)
        case None => accounts.initialize()
      }
      initialize.onComplete(result => initialized.tryComplete(result))(ExecutionContext.parasitic)
      val service = pekkoHttpService(application.config).apply(PekkoHttpServerConfig(maxRequestBodySize = 64 * 1024))
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
      initialize.flatMap(_ => Http().newServerAt("127.0.0.1", port).bindFlow(route)).onComplete {
        case Success(binding) =>
          val active  = new AtomicBoolean()
          val stopped = new AtomicBoolean()
          val maintenance = summon[ActorSystem].scheduler.scheduleWithFixedDelay(1.second, 1.second) { () =>
            if (!stopped.get() && active.compareAndSet(false, true))
              accounts.browser.maintenance().onComplete {
                case Success(_) => active.set(false)
                case Failure(_) => active.set(false); shutdown.run(CoordinatedShutdown.UnknownReason)
              }
          }
          shutdown.addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "stop-example-maintenance") { () =>
            stopped.set(true); maintenance.cancel(); Future.successful(Done)
          }
          shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "unbind-browser-auth-example")(() =>
            binding.unbind().map(_ => Done)
          )
          shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "drain-browser-auth-example")(() =>
            binding.terminate(5.seconds).map(_ => Done)
          )
          println(s"Synthetic $provider browser-auth example ready at $origin")
        case Failure(_) => startupFailed()
      }
    } catch { case scala.util.control.NonFatal(error) => initialized.tryFailure(error); startupFailed() }
  }
}
