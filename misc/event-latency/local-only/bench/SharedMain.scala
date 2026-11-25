package bench

import java.nio.file.Path
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
import spoonbill.auth.*
import spoonbill.pekko.*
import spoonbill.server.*
import spoonbill.state.javaSerialization.*

/**
 * The counter app behind the JDBC provider with shared views, as a provider
 * serving views from several nodes runs it: BrowserOwnership.SharedViews,
 * separate provider and clock pools, a host whose resolve locks and reads its
 * own session row, and authenticated views with an inline snapshot of the
 * projection. Usage: SharedMain <port>, under scripts/with-test-postgres.sh with
 * SPOONBILL_BENCH_SQL_PROFILE=1. The BENCH_SHARED_* switches select the
 * settings (see SharedSwitches); BENCH_PAYLOAD_BYTES pads the projection and
 * BENCH_MAINTENANCE_MS sets the maintenance cadence (default 30000).
 *
 * POST /bench-login provisions a session for the click client, POST
 * /bench-observe?count=&warmup= runs the observation-budget probe (see
 * ObservationProbe) and GET /bench-stats reports the counters of the click phase.
 */
object SharedMain {
  def main(args: Array[String]): Unit = {
    val Array(portArg) = args
    val switches       = SharedSwitches.fromEnvironment()
    val log = sys.env.getOrElse(
      "SPOONBILL_BENCH_SQL_LOG",
      throw new IllegalArgumentException(
        "jdbc-shared counts statements from the statement log: run it under scripts/with-test-postgres.sh with SPOONBILL_BENCH_SQL_PROFILE=1"
      )
    )
    serve(switches, Path.of(log), portArg.toInt)
  }

  def serve(switches: SharedSwitches, log: Path, port: Int): Unit = {
    val origin                = s"http://127.0.0.1:$port"
    given system: ActorSystem = ActorSystem("bench-shared")
    given ExecutionContext    = system.dispatcher
    given Materializer        = Materializer(system)

    val setup = new SharedSetup(switches, sys.env("SPOONBILL_JDBC_TEST_URL"))
    setup.initialize()
    setup.pools.awaitWarm()
    val presentation = BrowserPresentation[Counter, String](
      fresh = () => Counter(0),
      allowed = (_, _) => true,
      connected = (state, _, _) => state
    )
    val browser = new BrowserAuthentication[Future, Counter, String](
      setup.persistence,
      presentation,
      BrowserConfiguration(GuardedMain.scope, Set(origin), secureCookies = false),
      snapshots = setup.snapshots
    )
    val config = browser.configure(
      SpoonbillServiceConfig[Future, Counter, Any](
        stateLoader = StateLoader.default(Counter(0)),
        document = CounterPage.document
      )
    )
    val cadence     = sys.env.get("BENCH_MAINTENANCE_MS").map(_.toLong.millis).getOrElse(30.seconds)
    val maintenance = new MaintenanceSwitch
    system.scheduler.scheduleWithFixedDelay(cadence, cadence)(() => maintenance.run(() => browser.maintenance()))

    val configuration = browser.configuration
    val probe = new ObservationProbe(setup, new SqlProfile(log, setup.pools.control()), origin, configuration, maintenance)
    // The probe blocks while it waits for each settled call; it runs on its own thread.
    val probing = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
    val logins  = new AtomicLong()

    def stats(): String = {
      val s = browser.coordinatorStatistics
      BenchJson.obj(
        "mode"            -> BenchJson.str("jdbc-shared"),
        "switches"        -> switches.json,
        "payloadBytes"    -> SnapshotMain.payloadBytes.toString,
        "logins"          -> logins.get.toString,
        "hostResolves"    -> setup.host.resolutions.get.toString,
        "maintenanceRuns" -> maintenance.runs.get.toString,
        "coordinator" -> BenchJson.obj(
          "runningReads" -> s.runningReads.toString,
          "queuedReads"  -> s.queuedReads.toString,
          "queuedWrites" -> s.queuedWrites.toString,
          "bindingWaits" -> s.bindingWaits.toString,
          "rejections"   -> s.rejections.toString,
          "writerWaitMs" -> (s.writerWaitNanos / 1000000).toString
        ),
        "work"  -> setup.pools.workCounted.counters.json,
        "clock" -> setup.pools.clockCounted.counters.json
      )
    }
    // The same attributes the framework gives its binding cookie, for the loopback origin.
    def cookie(name: String, value: String): RawHeader =
      RawHeader("Set-Cookie", s"$name=$value; Path=/; HttpOnly; SameSite=Lax; Max-Age=${configuration.cookieMaxAgeSeconds}")
    val route = concat(
      path("bench-stats")(get(complete(HttpEntity(ContentTypes.`application/json`, stats())))),
      path("bench-login")(post(onSuccess(BenchLogin.provision(setup.persistence, hostSession = SharedHost.insert)) {
        provisioned =>
          logins.incrementAndGet()
          respondWithHeader(cookie(configuration.bindingCookieName, provisioned.binding)) {
            complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, provisioned.completionId.toString))
          }
      })),
      path("bench-observe")(post(parameterMap { parameters =>
        val count  = parameters.get("count").fold(20)(_.toInt)
        val warmup = parameters.get("warmup").fold(10)(_.toInt)
        // A probe that cannot run reports a failed verdict, never a partial profile.
        val report = Future {
          scala.util.Try(probe.run(count, warmup)).fold(
            error => BenchJson.obj("ok" -> "false", "violations" -> BenchJson.arr(Vector(BenchJson.str(error.toString)))),
            identity
          )
        }(probing)
        onSuccess(report)(json => complete(HttpEntity(ContentTypes.`application/json`, json)))
      })),
      pekkoHttpService(config).apply(PekkoHttpServerConfig())
    )
    Http().newServerAt("127.0.0.1", port).bindFlow(route).foreach { _ =>
      println(
        s"bench-shared ready on $port rows=${CounterPage.rows} maintenanceMs=${cadence.toMillis} " +
          s"payloadBytes=${SnapshotMain.payloadBytes} switches=${switches.json}"
      )
    }
  }
}
