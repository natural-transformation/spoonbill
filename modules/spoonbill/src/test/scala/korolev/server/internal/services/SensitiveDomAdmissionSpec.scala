package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import avocet.events.EventPhase
import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.{Context, Qsid}
import spoonbill.action.*
import spoonbill.effect.{Effect, Queue}
import spoonbill.internal.{ApplicationInstance, Frontend}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

class SensitiveDomAdmissionSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  private def suspendedGuard(checkToHold: Int): Future[org.scalatest.Assertion] = {
    val timeoutExecutor = Executors.newSingleThreadScheduledExecutor((run: Runnable) => {
      val thread = new Thread(run, "sensitive-dom-admission-test-timeout")
      thread.setDaemon(true)
      thread
    })
    def bounded[A](operation: Future[A], seconds: Long): Future[A] = {
      val result = Promise[A]()
      val timeout = timeoutExecutor.schedule(new Runnable {
        def run(): Unit = { result.tryFailure(new TimeoutException("Sensitive DOM admission regression stalled")); () }
      }, seconds, TimeUnit.SECONDS)
      operation.onComplete { value => timeout.cancel(false); result.tryComplete(value); () }
      result.future
    }
    val appRef = new AtomicReference(Option.empty[ApplicationInstance[Future, String, Any]])
    val armed = new AtomicBoolean(false)
    val checks = new AtomicInteger(0)
    val childInvoked = new AtomicInteger(0)
    val parentInvoked = new AtomicInteger(0)
    val programmaticInvoked = new AtomicInteger(0)
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val freshPropagated = Promise[Unit]()
    val guardClosed = Promise[Unit]()
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String) = Future.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId) = Future.successful(new SessionGuard[Future, String] {
        def authorize(state: String) =
          if (armed.get() && checks.incrementAndGet() == checkToHold) {
            entered.trySuccess(())
            release.future
          } else Future.unit
        def connected(state: String) = Future.successful(state)
        def close() = Future.successful { guardClosed.trySuccess(()); () }
      })
    }
    val context = Context[Future, String, Any]
    import context.*
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("initial"),
      document = _ => Html(body(div(
        event("click") { _ =>
          parentInvoked.incrementAndGet()
          freshPropagated.trySuccess(())
          Future.unit
        },
        button("Consume", event("click") { _ =>
          childInvoked.incrementAndGet()
          Future.unit
        })
      ))),
      heartbeatLimit = Some(2),
      sessionAccessControl = Some(control)
    )(executionContext)
    val service = new SessionsService(config, new PageService(config))
    val qsid = Qsid("dom-admission-device", UUID.randomUUID().toString)
    val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incoming = Queue[Future, String]()
    val programmaticAction = new Actions[Future, String, Unit].public(
      ActionName.parse("admission.programmatic").fold(error => fail(error.toString), identity),
      InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, _) =>
      programmaticInvoked.incrementAndGet()
      Future.successful(UiOutcome.unchanged[String])
    }
    def until(frontend: Frontend[Future], prefix: String, remaining: Int = 24,
      seen: Vector[String] = Vector.empty): Future[Vector[String]] =
      if (remaining == 0) Future.failed(new AssertionError("Expected control was not published"))
      else frontend.outgoingMessages.pull().flatMap {
        case Some(frame) if frame.startsWith(prefix) => Future.successful(seen :+ frame)
        case Some(frame) => until(frontend, prefix, remaining - 1, seen :+ frame)
        case None => Future.failed(new AssertionError("Connection closed before expected control"))
      }

    val workflow = for {
      _ <- service.initAppState(qsid, request)
      _ <- service.createAppIfNeeded(qsid, request, incoming.stream)
      app <- service.getApp(qsid).map(_.getOrElse(fail("Missing app")))
      _ = appRef.set(Some(app))
      _ <- until(app.frontend, "[21,")
      targets = app.topLevelComponentInstance.allEventHandlers.keys
        .filter(id => id.`type` == "click" && id.phase == EventPhase.Bubbling)
        .map(_.target.mkString).toVector
      _ = targets.size shouldBe 2
      target = targets.maxBy(_.split("_").length)
      _ = armed.set(true)
      _ <- incoming.enqueue(s"""[0,"0:$target:click"]""")
      _ <- entered.future
      // The queue's initial guard has returned. Hold either the application
      // dispatch guard (2) or the component handler's final guard (3).
      _ = checks.get() shouldBe checkToHold
      _ = childInvoked.get() shouldBe 0
      _ = parentInvoked.get() shouldBe 0
      _ <- incoming.enqueue("[10,\"1\"]")
      _ <- incoming.enqueue("[6]")
      heartbeat <- until(app.frontend, "[16]")
      _ = heartbeat.last shouldBe "[16]"
      _ = childInvoked.get() shouldBe 0
      _ = parentInvoked.get() shouldBe 0
      _ = release.success(())
      recovered <- until(app.frontend, "[25,")
      _ = recovered.last shouldBe "[25,1]"
      _ = childInvoked.get() shouldBe 0
      _ = parentInvoked.get() shouldBe 0
      // Use the event counter actually published by this dispatch. Superseded
      // work may be discarded before or after selecting its handler chain.
      counterPrefix = s"""[0,"$target","click","""
      nextCounter = recovered.filter(_.startsWith(counterPrefix)).lastOption
        .fold(0)(_.stripPrefix(counterPrefix).stripSuffix("]").trim.toInt)
      _ <- incoming.enqueue(s"""[0,"$nextCounter:$target:click"]""")
      _ <- freshPropagated.future
      _ <- until(app.frontend, counterPrefix)
      // Synthetic access has no browser event generation. Binding it after
      // recovery must capture the current generation, not its init event's 0.
      binding <- app.topLevelComponentInstance.browserAccess.actionBinding
      programmatic <- ActionDispatcher.public(programmaticAction, Vector.empty, binding)
    } yield {
      childInvoked.get() shouldBe 1
      parentInvoked.get() shouldBe 1
      programmatic match {
        case InvocationResult.Completed(_) => succeed
        case other => fail(s"Programmatic action after recovery was not admitted: $other")
      }
      programmaticInvoked.get() shouldBe 1
    }
    bounded(workflow, 10L).transformWith { outcome =>
      release.trySuccess(())
      val cleanup = appRef.get() match {
        case Some(app) => app.frontend.close().flatMap(_ => guardClosed.future)
        case None => incoming.stream.cancel()
      }
      bounded(cleanup, 3L).transformWith { cleanupOutcome =>
        timeoutExecutor.shutdownNow()
        Future.fromTry(outcome.flatMap(value => cleanupOutcome.map(_ => value)))
      }
    }
  }

  "Sensitive DOM admission" should "discard an event waiting for the application guard and admit a fresh post-barrier event" in {
    suspendedGuard(checkToHold = 2)
  }

  it should "stop the handler and propagation while the component guard waits across departure" in {
    suspendedGuard(checkToHold = 3)
  }
}
