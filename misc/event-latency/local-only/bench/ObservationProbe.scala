package bench

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.security.Identifiers.ConnectionId

/** Pauses scheduled maintenance for a measurement and waits for a sweep in progress to finish. */
final class MaintenanceSwitch {
  private val paused  = new AtomicBoolean()
  private val running = new AtomicInteger()
  val runs            = new AtomicLong()

  def run(sweep: () => Future[Unit])(using ExecutionContext): Unit = {
    // Count the sweep before reading the pause: pause() then either waits for
    // this sweep or this sweep sees the pause and does not start.
    running.incrementAndGet()
    if (paused.get()) running.decrementAndGet()
    else {
      runs.incrementAndGet()
      Try(sweep()).getOrElse(Future.unit).onComplete(_ => running.decrementAndGet())
    }
  }

  def pause(timeoutMillis: Long = 30000): Unit = {
    paused.set(true)
    val bound = System.nanoTime() + timeoutMillis * 1000000L
    while (running.get() > 0 && System.nanoTime() < bound) Thread.sleep(10)
    require(running.get() == 0, "A maintenance sweep did not finish in time")
  }

  def resume(): Unit = paused.set(false)
}

/** One bracketed observation: whether its check passed, its host resolve calls and its counted statements. */
final case class ProbeResult(index: Int, authorized: Boolean, hostCalls: Long, counts: Option[ObservationCounts])

/**
 * The observation-budget phase. A fixture signs in through the provider SPI and
 * the framework's completion endpoint, activates its session, claims an active
 * view and warms up. Then, with maintenance paused, it calls the real
 * provider's authorize once at a time. Each settled call is bracketed by log
 * markers, and the statements it executed on the provider and clock pools are
 * counted from the statement log once the last marker has arrived.
 */
final class ObservationProbe(
  setup: SharedSetup,
  profile: SqlProfile,
  origin: String,
  configuration: BrowserConfiguration,
  maintenance: MaintenanceSwitch
)(using ExecutionContext) {
  private def await[A](value: Future[A]): A = Await.result(value, 30.seconds)
  private def accepted[A](value: Either[BrowserFailure, A], step: String): A =
    value.fold(failure => throw new IllegalStateException(s"probe $step rejected with $failure"), identity)

  private lazy val fixture: BrowserView[String] = {
    val provisioned = await(BenchLogin.provision(setup.persistence, hostSession = SharedHost.insert))
    val token       = deliver(provisioned)
    val identity =
      accepted(await(setup.persistence.capture(provisioned.binding, Some(token), activate = true)), "capture")
    require(identity.principal.nonEmpty, "The fixture session did not activate")
    val qsid = Qsid("bench-probe", UUID.randomUUID().toString)
    accepted(
      await(setup.persistence.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false)),
      "claim"
    )
  }

  /** The session cookie, delivered by the framework's completion endpoint exactly as for a browser. */
  private def deliver(provisioned: BenchLogin.Provisioned): String = {
    val request = HttpRequest
      .newBuilder(URI.create(s"$origin/auth/complete"))
      .header("Cookie", s"${configuration.bindingCookieName}=${provisioned.binding}")
      .header("Origin", origin)
      .header("Content-Type", "text/plain")
      .POST(HttpRequest.BodyPublishers.ofString(provisioned.completionId.toString))
      .build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
    require(response.statusCode == 204, s"auth/complete answered ${response.statusCode}")
    val prefix = configuration.sessionCookieName + "="
    response.headers
      .allValues("set-cookie")
      .asScala
      .map(_.takeWhile(_ != ';'))
      .find(_.startsWith(prefix))
      .map(_.drop(prefix.length))
      .getOrElse(throw new IllegalStateException("auth/complete set no session cookie"))
  }

  private def observe(view: BrowserView[String]): Boolean =
    Try(await(setup.persistence.authorize(view))).toOption.exists(_.exists(view.identity.sameAuthority))

  def run(count: Int, warmup: Int): String = {
    require(count > 0 && warmup >= 0, "count must be positive and warmup non-negative")
    maintenance.pause()
    try measure(count, warmup)
    finally maintenance.resume()
  }

  private def measure(count: Int, warmup: Int): String = {
    val view = fixture
    (1 to warmup).foreach(_ => observe(view))
    val run = s"probe-${System.nanoTime()}"
    val observed = (1 to count).map { index =>
      profile.mark(s"$run:begin:$index")
      val before     = setup.host.resolutions.get()
      val authorized = observe(view)
      val calls      = setup.host.resolutions.get() - before
      profile.mark(s"$run:end:$index")
      (index, authorized, calls)
    }
    val settled = profile.awaitMarker(s"$run:end:$count")
    val entries = profile.entries()
    val results = observed.map { case (index, authorized, calls) =>
      val window = profile.window(entries, s"$run:begin:$index", s"$run:end:$index")
      ProbeResult(index, authorized, calls, window.map(ObservationCounts.of))
    }
    ProbeReport(ObservationBudget.of(setup.switches), results.toVector, settled, warmup).json
  }
}

/** The probe's verdict and its published statement profile. */
final case class ProbeReport(budget: ObservationBudget, results: Vector[ProbeResult], settled: Boolean, warmup: Int) {
  import BenchJson.*

  def violations: Vector[String] =
    Option.when(!settled)("the closing marker did not reach the statement log").toVector ++ results.flatMap { result =>
      val where = s"observation ${result.index}"
      result.counts match {
        case None => Vector(s"$where: missing boundary")
        case Some(counts) =>
          Vector(
            Option.when(!result.authorized)(s"$where: the check failed"),
            Option.when(counts.hostResolves != budget.resolves || result.hostCalls != budget.resolves)(
              s"$where: ${counts.hostResolves} host reads and ${result.hostCalls} resolve calls, expected ${budget.resolves}"
            ),
            Option.when(counts.clockCommits != budget.clockTransactions)(
              s"$where: ${counts.clockCommits} clock commits, expected ${budget.clockTransactions}"
            ),
            Option.when(counts.total > budget.statements)(
              s"$where: ${counts.total} statements, over the budget of ${budget.statements}"
            ),
            Option.when(counts.errors > 0 || counts.rollbacks > 0)(
              s"$where: ${counts.errors} failed statements and ${counts.rollbacks} rollbacks"
            )
          ).flatten
      }
    }

  def json: String = {
    val counted = results.flatMap(_.counts)
    def range(values: Vector[Double]): String =
      if (values.isEmpty) "null"
      else obj("min" -> num(values.min), "max" -> num(values.max), "mean" -> num(values.sum / values.size))
    val perObservation = counted.headOption.fold(Vector.empty[(String, String)])(_.metrics.map { case (name, _) =>
      name -> range(counted.map(_.metrics.collectFirst { case (`name`, value) => value.toDouble }.get))
    })
    val matches = counted.nonEmpty &&
      counted.forall(counts => counts.total == budget.statements && counts.isolationSets == budget.isolationSets)
    val shapes = counted
      .flatMap(_.statements)
      .groupBy(statement => (statement.role, statement.kind, statement.shape))
      .toVector
      .sortBy { case ((role, kind, shape), _) => (role, kind, shape) }
      .map { case ((role, kind, shape), all) =>
        obj("role" -> str(role), "kind" -> str(kind), "perObservation" -> num(all.size.toDouble / counted.size),
          "sql" -> str(shape))
      }
    val first = counted.headOption.fold(Vector.empty[String])(_.statements.map { statement =>
      obj("role" -> str(statement.role), "kind" -> str(statement.kind), "sql" -> str(statement.shape))
    })
    obj(
      "ok"               -> bool(violations.isEmpty),
      "observations"     -> results.size.toString,
      "warmup"           -> warmup.toString,
      "budget"           -> budget.json,
      "matchesModel"     -> bool(matches),
      "perObservation"   -> obj(perObservation*),
      "hostCalls"        -> range(results.map(_.hostCalls.toDouble)),
      "violations"       -> arr(violations.map(str)),
      "shapes"           -> arr(shapes),
      "firstObservation" -> arr(first)
    )
  }
}
