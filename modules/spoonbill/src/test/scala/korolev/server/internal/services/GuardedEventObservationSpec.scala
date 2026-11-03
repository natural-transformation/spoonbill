package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import avocet.events.EventPhase
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*
import spoonbill.{Context, Qsid}
import spoonbill.effect.{Effect, Queue}
import spoonbill.internal.{ApplicationInstance, Frontend}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Each guard observation can be a full provider transaction (a PostgreSQL
 * round-trip sequence for the JDBC provider), so their number per user event
 * sets guarded latency. A click whose handler transitions state observes
 * authority once at each boundary, never zero and never twice in a row:
 * event admission, the transition's read, its write (current and candidate),
 * the render's snapshot, and the patch's release (current and produced).
 * Spoonbill 8.0.1 made 14 observations for the same click.
 */
class GuardedEventObservationSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  "A guarded click" should "observe authority once per security boundary" in {
    val timeoutExecutor = Executors.newSingleThreadScheduledExecutor { (run: Runnable) =>
      val thread = new Thread(run, "guarded-event-observation-test-timeout")
      thread.setDaemon(true)
      thread
    }
    def bounded[A](operation: Future[A], seconds: Long): Future[A] = {
      val result = Promise[A]()
      val timeout = timeoutExecutor.schedule(
        new Runnable { def run(): Unit = { result.tryFailure(new TimeoutException("Guarded click stalled")); () } },
        seconds,
        TimeUnit.SECONDS
      )
      operation.onComplete { value => timeout.cancel(false); result.tryComplete(value); () }
      result.future
    }
    val appRef       = new AtomicReference(Option.empty[ApplicationInstance[Future, String, Any]])
    val counting     = new AtomicBoolean(false)
    val observations = new ConcurrentLinkedQueue[List[String]]()
    val guardClosed  = Promise[Unit]()
    def observe(states: List[String]): Future[Unit] = {
      if (counting.get()) observations.add(states)
      Future.unit
    }
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String) = Future.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId) =
        Future.successful(new SessionGuard[Future, String] {
          val applicationStateInitialization = ApplicationStateInitialization.Retained[Future]()
          // Both entry points count as one observation each.
          def authorize(state: String) = observe(List(state))
          override private[spoonbill] def authorizeEach(states: List[String])(using Effect[Future]) =
            observe(states)
          def connected(state: String) = Future.successful(state)
          def close()                  = Future.successful { guardClosed.trySuccess(()); () }
        })
    }
    val context = Context[Future, String, Any]
    import context.*
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("initial"),
      document = state =>
        Html(body(p(state), button("Act", event("click")(access => access.transition(_ => "clicked"))))),
      sessionAccessControl = Some(control)
    )(executionContext)
    val service  = new SessionsService(config, new PageService(config))
    val qsid     = Qsid("observation-device", UUID.randomUUID().toString)
    val request  = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incoming = Queue[Future, String]()
    def until(frontend: Frontend[Future], prefix: String, remaining: Int = 24): Future[Unit] =
      if (remaining == 0) Future.failed(new AssertionError("Expected frame was not published"))
      else
        frontend.outgoingMessages.pull().flatMap {
          case Some(frame) if frame.startsWith(prefix) => Future.unit
          case Some(_)                                 => until(frontend, prefix, remaining - 1)
          case None                                    => Future.failed(new AssertionError("Connection closed"))
        }

    val workflow = for {
      _   <- service.initAppState(qsid, request)
      _   <- service.createAppIfNeeded(qsid, request, incoming.stream)
      app <- service.getApp(qsid).map(_.getOrElse(fail("Missing app")))
      _    = appRef.set(Some(app))
      _   <- until(app.frontend, "[21,")
      target = app.topLevelComponentInstance.allEventHandlers.keys
                 .collectFirst { case id if id.`type` == "click" && id.phase == EventPhase.Bubbling => id.target.mkString }
                 .getOrElse(fail("Missing click handler"))
      _  = counting.set(true)
      _ <- incoming.enqueue(s"""[0,"0:$target:click"]""")
      // The patch is released before the handler's event-counter update.
      _ <- until(app.frontend, s"""[0,"$target","click",""")
    } yield {
      counting.set(false)
      observations.asScala.toList shouldBe List(
        List("initial"),            // event admission, also covering the first handler
        List("initial"),            // the transition reads current state
        List("initial", "clicked"), // its write: current and candidate together
        List("clicked"),            // the render's snapshot, whose root is current state
        List("clicked", "clicked")  // the patch's release: current and produced together
      )
    }

    bounded(workflow, 10L).transformWith { outcome =>
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
