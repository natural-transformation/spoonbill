package bench

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.auth.*
import spoonbill.pekko.*
import spoonbill.recovery.{PayloadRecovery, PayloadSnapshotBinding, RecoveryStatistics}
import spoonbill.security.jdbc.*
import spoonbill.server.*
import spoonbill.snapshot.{SchemaId, SchemaVersion, SnapshotFormat, SnapshotLimits, StateSchema}
import spoonbill.state.javaSerialization.*

/** The projected presentation: the count, plus padding that sets the payload size. */
final case class CounterSnapshot(count: Int, padding: String) derives StateSchema

/**
 * The counter app behind the JDBC provider with authenticated views and a
 * durable projection of the count. Usage: SnapshotMain <jdbc-snapshot|jdbc-reference> <port>.
 * jdbc-snapshot saves the projection inline through persistence.projectedSnapshots;
 * jdbc-reference publishes payload references through
 * PayloadRecovery.projectedWithPayloads and stores the bytes in the bench
 * payload table. BENCH_PAYLOAD_BYTES pads the projection (default 1024),
 * BENCH_PAYLOAD_DELAY_MS slows every payload put and get (default 0) and
 * BENCH_MAINTENANCE_MS sets the maintenance cadence (default 30000). POST
 * /bench-login provisions a session for the client; GET /bench-stats reports
 * the counters from which the run asserts the recovery plan's commit budget.
 */
object SnapshotMain {
  enum Storage { case Inline, Reference }

  val payloadBytes: Int      = sys.env.get("BENCH_PAYLOAD_BYTES").map(_.toInt).getOrElse(1024)
  val payloadDelayMs: Double = sys.env.get("BENCH_PAYLOAD_DELAY_MS").map(_.toDouble).getOrElse(0.0)
  private val padding        = "x".repeat(payloadBytes)

  /** One schema version; its limits grow with the padding so large payloads still encode. */
  val format: SnapshotFormat[CounterSnapshot] = {
    val ceiling = math.max(1024 * 1024, payloadBytes + 4096)
    new SnapshotFormat[CounterSnapshot](
      SchemaId.parse("bench.counter").toOption.get,
      SchemaVersion.fromInt(1).toOption.get,
      SnapshotLimits.create(maxUtf8Bytes = ceiling, maxStringUtf8Bytes = ceiling).toOption.get
    )
  }
  def project(state: Counter): CounterSnapshot                 = CounterSnapshot(state.count, padding)
  def restore(fresh: Counter, saved: CounterSnapshot): Counter = fresh.copy(count = saved.count)

  def main(args: Array[String]): Unit = {
    val Array(mode, portArg) = args
    val storage = mode match {
      case "jdbc-snapshot"  => Storage.Inline
      case "jdbc-reference" => Storage.Reference
      case other => throw new IllegalArgumentException(s"Unknown mode $other; use jdbc-snapshot or jdbc-reference")
    }
    serve(mode, storage, portArg.toInt)
  }

  def serve(mode: String, storage: Storage, port: Int): Unit = {
    val origin                = s"http://127.0.0.1:$port"
    given system: ActorSystem = ActorSystem("bench-snapshot")
    given ExecutionContext    = system.dispatcher
    given Materializer        = Materializer(system)

    // The same pool and blocking executor as the other JDBC modes. The provider
    // sees the pool through the transaction-classifying wrapper; the payload
    // store uses it directly and keeps its own counters.
    val pool = new HikariConfig()
    pool.setJdbcUrl(sys.env("SPOONBILL_JDBC_TEST_URL"))
    pool.setMaximumPoolSize(32)
    val source   = new HikariDataSource(pool)
    val counted  = new CountingDataSource(source)
    val blocking = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(16))
    // Benchmark-only key; never use a fixed key for real deployments.
    val key = MessageDigest.getInstance("SHA-256").digest("bench-only".getBytes("UTF-8"))
    val persistence = new JdbcBrowserPersistence[Future, String](
      counted,
      blocking,
      GuardedMain.scope.realm,
      GuardedMain.scope.cookieNamespace,
      () => Instant.now(),
      new BenchLogin.Host,
      JdbcCredentialVault.aesGcm(key),
      BrowserOwnership.ExclusiveScope
    )
    val payloads = new BenchPayloadStore(source, blocking, payloadDelayMs)
    // The provider's explicit migration, through V7, and the bench payload table, once.
    Using.resource(source.getConnection) { connection =>
      connection.setAutoCommit(false)
      persistence.initialize(connection)
      BenchPayloadStore.schema.foreach(text => Using.resource(connection.createStatement())(_.executeUpdate(text)))
      connection.commit()
    }
    val payloadBinding: Option[PayloadSnapshotBinding[Future, String, Counter]] =
      Option.when(storage == Storage.Reference)(
        PayloadRecovery.projectedWithPayloads[Future, String, Counter, CounterSnapshot](
          persistence.snapshotReferences(),
          payloads,
          format,
          project,
          restore
        )
      )
    val projected = payloadBinding.fold(persistence.projectedSnapshots[Counter, CounterSnapshot](format, project, restore))(_.snapshots)
    val counters  = new SnapshotCounters[Counter]
    val presentation = BrowserPresentation[Counter, String](
      fresh = () => Counter(0),
      allowed = (_, _) => true,
      connected = (state, _, _) => state
    )
    val browser = new BrowserAuthentication[Future, Counter, String](
      persistence,
      presentation,
      BrowserConfiguration(GuardedMain.scope, Set(origin), secureCookies = false),
      snapshots = counters.wrap(projected)
    )
    val config = browser.configure(
      SpoonbillServiceConfig[Future, Counter, Any](
        stateLoader = StateLoader.default(Counter(0)),
        document = CounterPage.document
      )
    )
    val cadence = sys.env.get("BENCH_MAINTENANCE_MS").map(_.toLong.millis).getOrElse(30.seconds)
    // Browser maintenance first, then the binding's payload deletes, outside the coordinator's turns.
    system.scheduler.scheduleWithFixedDelay(cadence, cadence) { () =>
      browser.maintenance().flatMap(_ => payloadBinding.fold(Future.unit)(_.maintenance())); ()
    }
    val logins = new AtomicLong()
    def stats(): String = {
      val s = browser.coordinatorStatistics
      s"""{"mode":"$mode","payloadBytes":$payloadBytes,"payloadDelayMs":$payloadDelayMs,"logins":${logins.get},""" +
        s""""coordinator":{"runningReads":${s.runningReads},"queuedReads":${s.queuedReads},""" +
        s""""queuedWrites":${s.queuedWrites},"bindingWaits":${s.bindingWaits},"rejections":${s.rejections},""" +
        s""""writerWaitMs":${s.writerWaitNanos / 1000000}},""" +
        s""""snapshots":${counters.json},"transactions":${counted.counters.json},""" +
        s""""recovery":${payloadBinding.fold("null")(binding => recoveryJson(binding.statistics))},""" +
        s""""payloads":${if (storage == Storage.Reference) payloads.json else "null"}}"""
    }
    val configuration = browser.configuration
    // The same attributes the framework gives its binding cookie, for the loopback origin.
    def cookie(name: String, value: String): RawHeader =
      RawHeader("Set-Cookie", s"$name=$value; Path=/; HttpOnly; SameSite=Lax; Max-Age=${configuration.cookieMaxAgeSeconds}")
    val route = concat(
      path("bench-stats")(get(complete(HttpEntity(ContentTypes.`application/json`, stats())))),
      path("bench-login")(post(onSuccess(BenchLogin.provision(persistence)) { provisioned =>
        logins.incrementAndGet()
        respondWithHeader(cookie(configuration.bindingCookieName, provisioned.binding)) {
          complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, provisioned.completionId.toString))
        }
      })),
      pekkoHttpService(config).apply(PekkoHttpServerConfig())
    )
    Http().newServerAt("127.0.0.1", port).bindFlow(route).foreach { _ =>
      println(
        s"bench-snapshot-$mode ready on $port rows=${CounterPage.rows} maintenanceMs=${cadence.toMillis} " +
          s"payloadBytes=$payloadBytes payloadDelayMs=$payloadDelayMs"
      )
    }
  }

  private def recoveryJson(r: RecoveryStatistics): String =
    s"""{"reservations":${r.reservations},"publications":${r.publications},"statusLookups":${r.statusLookups},""" +
      s""""uploads":${r.uploads},"downloads":${r.downloads},"deletes":${r.deletes},""" +
      s""""reconciledPublications":${r.reconciledPublications},"finalDeletes":${r.finalDeletes},""" +
      s""""uncertainDeletes":${r.uncertainDeletes},"staleClaims":${r.staleClaims},""" +
      s""""downgradedDeletes":${r.downgradedDeletes}}"""
}
