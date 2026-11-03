package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import avocet.events.EventPhase
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeoutException, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.{Context, Qsid}
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.internal.{ApplicationInstance, Frontend}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Covers the revision checks of a guarded DOM event. Queue admission authorizes
 * the event once; the application then selects its handlers without another
 * authorization. A render published while that authorization is pending makes
 * the event obsolete: it must not run either handler, close the connection or
 * report an application error.
 */
class ObsoleteDomEventSelectionSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  // Records this session only; the Metrics counter is shared by the whole process.
  private final class RecordingReporter extends Reporter {
    val discards  = new AtomicInteger()
    val errors    = new ConcurrentLinkedQueue[String]()
    val discarded = Promise[Unit]()
    def debug(message: String): Unit =
      if (message == "Obsolete browser event discarded") {
        discards.incrementAndGet(); discarded.trySuccess(()); ()
      }
    def debug(message: String, a: Any): Unit                 = ()
    def debug(message: String, a: Any, b: Any): Unit         = ()
    def debug(message: String, a: Any, b: Any, c: Any): Unit = ()
    def info(message: String): Unit                          = ()
    def warning(message: String): Unit                       = ()
    def warning(message: String, cause: Throwable): Unit     = ()
    def error(message: String): Unit                         = { errors.add(message); () }
    def error(message: String, cause: Throwable): Unit       = { errors.add(message); () }
  }

  "A guarded DOM event" should "be discarded when a newer render wins during its admission authorization" in {
    val timeoutExecutor = Executors.newSingleThreadScheduledExecutor { (run: Runnable) =>
      val thread = new Thread(run, "obsolete-dom-event-selection-test-timeout")
      thread.setDaemon(true)
      thread
    }
    def bounded[A](operation: Future[A], seconds: Long): Future[A] = {
      val result = Promise[A]()
      val timeout = timeoutExecutor.schedule(
        new Runnable {
          def run(): Unit = { result.tryFailure(new TimeoutException("Obsolete DOM event selection stalled")); () }
        },
        seconds,
        TimeUnit.SECONDS
      )
      operation.onComplete { value =>
        timeout.cancel(false); result.tryComplete(value); ()
      }
      result.future
    }
    val appRef         = new AtomicReference(Option.empty[ApplicationInstance[Future, String, Any]])
    val reporter       = new RecordingReporter
    val armed          = new AtomicBoolean(false)
    val checks         = new AtomicInteger(0)
    val entered        = Promise[Unit]()
    val release        = Promise[Unit]()
    val guardClosed    = Promise[Unit]()
    val oldInvocations = new AtomicInteger(0)
    val newInvocations = new AtomicInteger(0)
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String) = Future.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId) =
        Future.successful(new SessionGuard[Future, String] {
          val applicationStateInitialization = ApplicationStateInitialization.Retained[Future]()
          override val ownershipEpoch        = Some(ViewOwnershipEpoch.initial)
          // Once armed, check 1 is the event's queue admission. It is the only
          // authorization before the application selects handlers.
          def authorize(state: String) =
            if (armed.get() && checks.incrementAndGet() == 1) {
              entered.trySuccess(())
              release.future
            } else Future.unit
          def connected(state: String) = Future.successful(state)
          def close()                  = Future.successful { guardClosed.trySuccess(()); () }
        })
    }
    val context = Context[Future, String, Any]
    import context.*
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("initial"),
      // The newer render keeps the button's element but replaces its handler,
      // so an event rebound to the newer view would be visible.
      document = state =>
        Html(
          body(
            p(state),
            button(
              "Act",
              event("click") { _ =>
                if (state == "initial") oldInvocations.incrementAndGet() else newInvocations.incrementAndGet()
                Future.unit
              }
            )
          )
        ),
      sessionAccessControl = Some(control),
      reporter = reporter
    )(executionContext)
    val service        = new SessionsService(config, new PageService(config))
    val qsid           = Qsid("obsolete-selection-device", UUID.randomUUID().toString)
    val request        = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incoming       = Queue[Future, String]()
    val initialization = """^\[19,"[^"]+","([^"]+)","0",.*""".r
    val patch          = """^\[20,"[^"]+","[0-9]+","([0-9]+)",.*""".r
    def revision(frames: Vector[String]): Long =
      frames.collect { case patch(next) => next.toLong }.lastOption.getOrElse(0L)
    def click(connection: String, revision: Long, target: String): String =
      s"""[7,"$connection:$revision:0:0:$target:click"]"""
    def until(
      frontend: Frontend[Future],
      prefix: String,
      remaining: Int = 24,
      seen: Vector[String] = Vector.empty
    ): Future[Vector[String]] =
      if (remaining == 0) Future.failed(new AssertionError("Expected frame was not published"))
      else
        frontend.outgoingMessages.pull().flatMap {
          case Some(frame) if frame.startsWith(prefix) => Future.successful(seen :+ frame)
          case Some(frame)                             => until(frontend, prefix, remaining - 1, seen :+ frame)
          case None                                    => Future.failed(new AssertionError("Connection closed before expected frame"))
        }

    val workflow = for {
      _         <- service.initAppState(qsid, request)
      _         <- service.createAppIfNeeded(qsid, request, incoming.stream)
      app       <- service.getApp(qsid).map(_.getOrElse(fail("Missing app")))
      _          = appRef.set(Some(app))
      delivered <- until(app.frontend, "[21,")
      connection =
        delivered.collectFirst { case initialization(value) => value }.getOrElse(fail("Missing guarded initialization"))
      deliveredRevision = revision(delivered)
      targets = app.topLevelComponentInstance.allEventHandlers.keys
                  .filter(id => id.`type` == "click" && id.phase == EventPhase.Bubbling)
                  .map(_.target.mkString)
                  .toVector
      _      = targets.size shouldBe 1
      target = targets.head
      _      = armed.set(true)
      _     <- incoming.enqueue(click(connection, deliveredRevision, target))
      // The then-current revision passed its bounds check. Queue admission's
      // authorization is now held, before the obsolete-revision check.
      _ <- entered.future
      _  = checks.get() shouldBe 1
      _ <- app.topLevelComponentInstance.browserAccess.transition(_ => "updated")
      published <- until(app.frontend, "[20,")
      publishedRevision = revision(published)
      _                 = publishedRevision should be > deliveredRevision
      _                 = release.success(())
      _                <- reporter.discarded.future
      _                 = oldInvocations.get() shouldBe 0
      _                 = newInvocations.get() shouldBe 0
      // A current click is a same-queue barrier: it runs only after the obsolete
      // event has finished, and only on the same open connection.
      _ <- incoming.enqueue(click(connection, publishedRevision, target))
      _ <- until(app.frontend, s"""[0,"$target","click",""")
    } yield {
      oldInvocations.get() shouldBe 0
      newInvocations.get() shouldBe 1
      reporter.discards.get() shouldBe 1
      reporter.errors.isEmpty shouldBe true
      guardClosed.isCompleted shouldBe false
    }

    bounded(workflow, 10L).transformWith { outcome =>
      release.trySuccess(())
      val cleanup = appRef.get() match {
        case Some(app) => app.frontend.close().flatMap(_ => guardClosed.future)
        case None      => incoming.stream.cancel()
      }
      bounded(cleanup, 3L).transformWith { cleanupOutcome =>
        timeoutExecutor.shutdownNow()
        Future.fromTry(outcome.flatMap(value => cleanupOutcome.map(_ => value)))
      }
    }
  }
}
