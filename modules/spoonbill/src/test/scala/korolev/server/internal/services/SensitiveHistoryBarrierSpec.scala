package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import spoonbill.{Qsid, Router, SensitiveRegion}
import spoonbill.effect.{Effect, Queue}
import spoonbill.internal.{ApplicationInstance, Frontend}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.sensitive.*
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

class SensitiveHistoryBarrierSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private def accepted[A](result: Either[SensitiveError, A]): A = result.fold(error => fail(error.toString), identity)

  "Sensitive history barrier" should "release a waiting disclosure before routing and confirm only after serialized route mutation" in {
    val timeoutExecutor = Executors.newSingleThreadScheduledExecutor((run: Runnable) => {
      val thread = new Thread(run, "sensitive-history-test-timeout")
      thread.setDaemon(true)
      thread
    })
    def bounded[A](operation: Future[A], seconds: Long, message: String): Future[A] = {
      val result = Promise[A]()
      val timeout = timeoutExecutor.schedule(new Runnable {
        def run(): Unit = { result.tryFailure(new TimeoutException(message)); () }
      }, seconds, TimeUnit.SECONDS)
      operation.onComplete { value => timeout.cancel(false); result.tryComplete(value); () }
      result.future
    }
    val appRef = new AtomicReference(Option.empty[ApplicationInstance[Future, String, Any]])
    val region = accepted(RegionId.parse("recovery"))
    val purpose = accepted(Purpose.parse("mfa.recovery"))
    val audience = Audience.fromUuid(UUID.randomUUID())
    val routeEntered = Promise[Unit]()
    val finishRoute = Promise[String]()
    val guardClosed = Promise[Unit]()
    val next = PathAndQuery.fromString("/next")
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String) = Future.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId) = Future.successful(new SessionGuard[Future, String] {
        def authorize(state: String) = Future.unit
        def connected(state: String) = Future.successful(state)
        def close() = Future.successful { guardClosed.trySuccess(()); () }
        override def sensitive = Some(new SensitiveAccess[Future, String] {
          def authorize(purpose: Purpose, state: String) = Future.successful(audience)
        })
      })
    }
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("initial"),
      router = Router[Future, String](fromState = PartialFunction.empty, toState = {
        case path if path == next => _ => { routeEntered.trySuccess(()); finishRoute.future }
      }),
      document = state => Html(body(div(state), SensitiveRegion[spoonbill.Context.Binding[Future, String, Any]](region))),
      sessionAccessControl = Some(control)
    )(executionContext)
    val service = new SessionsService(config, new PageService(config))
    val qsid = Qsid("history-device", UUID.randomUUID().toString)
    val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incoming = Queue[Future, String]()
    def until(frontend: Frontend[Future], prefix: String, remaining: Int = 16): Future[String] =
      if (remaining == 0) Future.failed(new AssertionError("Expected control was not published"))
      else frontend.outgoingMessages.pull().flatMap {
        case Some(frame) if frame.startsWith(prefix) => Future.successful(frame)
        case Some(_) => until(frontend, prefix, remaining - 1)
        case None => Future.failed(new AssertionError("Connection closed before control"))
      }

    val workflow = for {
      _ <- service.initAppState(qsid, request)
      _ <- service.createAppIfNeeded(qsid, request, incoming.stream)
      app <- service.getApp(qsid).map(_.getOrElse(fail("Missing app")))
      _ = appRef.set(Some(app))
      _ <- until(app.frontend, "[21,")
      disclosure = app.frontend.runUserAction(app.frontend.presentSensitive(region, purpose,
        accepted(SensitivePayload.textList(Vector("synthetic-code"))), 1.minute))
      _ <- until(app.frontend, "[22,")
      // No disclosure ack: raw history must retire it to free the user queue.
      _ <- incoming.enqueue("[3,\"/next\"]")
      clear <- until(app.frontend, "[23,")
      outcome <- disclosure
      _ <- routeEntered.future
      waiting = app.frontend.outgoingMessages.pull()
      _ = waiting.isCompleted shouldBe false
      _ = finishRoute.success("next")
      first <- waiting
      barrier <- first match {
        case Some(frame) if frame.startsWith("[24,") => Future.successful(frame)
        case Some(_) => until(app.frontend, "[24,")
        case None => Future.failed(new AssertionError("Closed before navigation barrier"))
      }
      state <- app.topLevelComponentInstance.browserAccess.state
    } yield {
      clear should startWith("[23,")
      outcome shouldBe DisclosureOutcome.Uncertain
      barrier shouldBe "[24,1]"
      state shouldBe "next"
    }
    bounded(workflow, 10L, "Sensitive history regression timed out").transformWith { result =>
      // Unblock any route work before closing, including on a failed assertion
      // or timeout. The cleanup itself has a separate bounded completion.
      finishRoute.trySuccess("next")
      val cleanup = appRef.get() match {
        case Some(app) => app.frontend.close().flatMap(_ => guardClosed.future)
        case None => incoming.stream.cancel()
      }
      bounded(cleanup, 3L, "Sensitive history cleanup timed out").transformWith { cleanupResult =>
        timeoutExecutor.shutdownNow()
        result match {
          case scala.util.Failure(error) => Future.failed(error)
          case scala.util.Success(assertion) => cleanupResult match {
            case scala.util.Failure(error) => Future.failed(error)
            case scala.util.Success(_) => Future.successful(assertion)
          }
        }
      }
    }
  }
}
