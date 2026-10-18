package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import avocet.events.EventPhase
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.{Executors, TimeoutException, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import spoonbill.{ActionForms, Context, Qsid, SensitiveRegion}
import spoonbill.action.*
import spoonbill.effect.{Effect, Queue}
import spoonbill.internal.{jsonEscape, ApplicationInstance, Frontend}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.sensitive.*
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Exercises actual typed bindings and revision-bearing browser messages. Output
 * inspected after acknowledgement remains outside browser delivery. Zero
 * transition requests are separately checked by ActionFormsExecutionSpec.
 */
class NoOpSensitiveAcknowledgementSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  private enum Scenario {
    case NoUpdate, IdentityModification, StateModification, QueuedModification, RevokedAuthority, ReplacedOwner
  }

  private def accepted[A](result: Either[SensitiveError, A]): A =
    result.fold(_ => fail("Invalid synthetic sensitive value"), identity)

  private def frame(code: Int, argument: String): String = {
    val escaped = new mutable.StringBuilder()
    jsonEscape(escaped, argument, unicode = true)
    s"""[$code,"$escaped"]"""
  }

  private def submit(connection: String, revision: Long, target: String, fields: Vector[(String, String)]): String = {
    val encoded = fields.map { case (name, value) =>
      s"${URLEncoder.encode(name, UTF_8)}=${URLEncoder.encode(value, UTF_8)}"
    }.mkString("&")
    val event = s"0:$target:submit:$encoded"
    // Check both the empty-form trailing colon and escaped field construction
    // against the real decoder before sending through the guarded wire path.
    Frontend.decodeDomEvent(event).submission.get.toOption.get.fields shouldBe fields
    frame(7, s"$connection:$revision:0:$event")
  }

  private def exercise(scenario: Scenario): Future[org.scalatest.Assertion] = {
    val timer = Executors.newSingleThreadScheduledExecutor { (run: Runnable) =>
      val thread = new Thread(run, "noop-sensitive-ack-test-timeout")
      thread.setDaemon(true)
      thread
    }
    def bounded[A](future: Future[A], seconds: Long): Future[A] = {
      val result = Promise[A]()
      val timeout = timer.schedule(
        new Runnable {
          def run(): Unit = {
            result.tryFailure(new TimeoutException("Typed acknowledgement regression timed out"))
            ()
          }
        },
        seconds,
        TimeUnit.SECONDS
      )
      future.onComplete { outcome =>
        timeout.cancel(false); result.tryComplete(outcome); ()
      }
      result.future
    }

    val appRef                 = new AtomicReference(Option.empty[ApplicationInstance[Future, String, Any]])
    val guardClosed            = Promise[Unit]()
    val authorityValid         = new AtomicBoolean(true)
    val owner                  = new AtomicReference(ViewOwnershipEpoch.initial)
    val revealInvoked          = new AtomicInteger()
    val completionInvoked      = new AtomicInteger()
    val reducerInvoked         = new AtomicInteger()
    val confirmInvoked         = new AtomicInteger()
    val rejected               = new AtomicInteger()
    val priorTransitionEntered = Promise[Unit]()
    val releasePriorTransition = Promise[String]()
    val region                 = accepted(RegionId.parse("setup"))
    val purpose                = accepted(Purpose.parse("setup"))
    val audience               = Audience.fromUuid(UUID.randomUUID())
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String) = Future.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId) = {
        val attachedOwner = owner.get()
        Future.successful(new SessionGuard[Future, String] {
          val applicationStateInitialization = ApplicationStateInitialization.Retained[Future]()
          override val ownershipEpoch        = Some(attachedOwner)
          def authorize(state: String) =
            if (authorityValid.get() && owner.get() == attachedOwner) Future.unit
            else Future.failed(new SessionAccessDenied)
          def connected(state: String) = Future.successful(state)
          def close()                  = Future.successful { guardClosed.trySuccess(()); () }
          override def sensitive = Some(new SensitiveAccess[Future, String] {
            def authorize(purpose: Purpose, state: String) = Future.successful(audience)
          })
        })
      }
    }
    val actions             = new Actions[Future, String, Unit]
    def name(value: String) = ActionName.parse(value).fold(error => fail(error.toString), identity)
    val reveal = actions.public(name("setup.reveal"), InputSchema.empty, PublicPolicy.allow[Future, Unit]) {
      (_, context) =>
        revealInvoked.incrementAndGet()
        Future.successful(
          context.presentSensitive[String](
            region,
            purpose,
            accepted(SensitivePayload.textList(Vector("synthetic-setup"))),
            1.minute
          ) { outcome =>
            outcome shouldBe DisclosureOutcome.BrowserProcessed
            completionInvoked.incrementAndGet()
            scenario match {
              case Scenario.IdentityModification => StateUpdate.Modify[String](identity)
              case Scenario.StateModification    => StateUpdate.Modify[String](_ => "confirmed setup")
              case Scenario.QueuedModification =>
                StateUpdate.Modify[String] { current =>
                  reducerInvoked.incrementAndGet()
                  s"$current:completed"
                }
              case _ => StateUpdate.noUpdate[String]
            }
          }
        )
    }
    val fieldName    = FieldName.parse("confirmation").fold(error => fail(error.toString), identity)
    val confirmation = "synthetic + &: \"confirmation\""
    val confirm =
      actions.public(name("setup.confirm"), InputSchema.text(fieldName, 128), PublicPolicy.allow[Future, String]) {
        (value, _) =>
          value shouldBe confirmation
          confirmInvoked.incrementAndGet()
          Future.successful(UiOutcome.noUpdate[String])
      }
    val onRejected: ActionRejection => StateUpdate[String] = _ => {
      rejected.incrementAndGet()
      StateUpdate.noUpdate[String]
    }
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("initial"),
      document = state =>
        Html(
          body(
            div(state),
            form(id := "reveal-setup", ActionForms.onSubmit[Future, String, Any, Unit](reveal, onRejected)),
            form(id := "confirm-setup", ActionForms.onSubmit[Future, String, Any, String](confirm, onRejected)),
            SensitiveRegion[Context.Binding[Future, String, Any]](region)
          )
        ),
      sessionAccessControl = Some(control)
    )(executionContext)
    val service  = new SessionsService(config, new PageService(config))
    val qsid     = Qsid("noop-ack-device", UUID.randomUUID().toString)
    val request  = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incoming = Queue[Future, String]()
    def until(
      frontend: Frontend[Future],
      prefix: String,
      remaining: Int = 32,
      seen: Vector[String] = Vector.empty
    ): Future[Vector[String]] =
      if (remaining == 0) Future.failed(new AssertionError("Expected test frame was not published"))
      else
        frontend.outgoingMessages.pull().flatMap {
          case Some(value) if value.startsWith(prefix) => Future.successful(seen :+ value)
          case Some(value)                             => until(frontend, prefix, remaining - 1, seen :+ value)
          case None                                    => Future.failed(new AssertionError("Transport closed before expected test frame"))
        }
    val initialization = """^\[19,"[^"]+","([^"]+)","0",.*""".r
    val patch          = """^\[20,"[^"]+","[0-9]+","([0-9]+)",.*""".r
    val sensitive      = """^\[22,"([^"]+)","([^"]+)","([^"]+)",.*""".r
    def revision(frames: Vector[String]): Option[Long] =
      frames.collect { case patch(next) => next.toLong }.lastOption
    def counter(target: String) = s"""[0,"$target","submit","""
    def publishedQueuedResult(frontend: Frontend[Future], remaining: Int = 4): Future[Unit] =
      if (remaining == 0) Future.failed(new AssertionError("Queued typed update was not published"))
      else
        until(frontend, "[20,").flatMap { frames =>
          if (frames.last.contains("intervening:completed")) Future.unit
          else publishedQueuedResult(frontend, remaining - 1)
        }

    val workflow = for {
      _         <- service.initAppState(qsid, request)
      _         <- service.createAppIfNeeded(qsid, request, incoming.stream)
      app       <- service.getApp(qsid).map(_.getOrElse(fail("Missing test application")))
      _          = appRef.set(Some(app))
      delivered <- until(app.frontend, "[21,")
      connection =
        delivered.collectFirst { case initialization(value) => value }.getOrElse(fail("Missing guarded initialization"))
      deliveredRevision = revision(delivered).getOrElse(0L)
      targets = app.topLevelComponentInstance.allEventHandlers.keys
                  .filter(event => event.`type` == "submit" && event.phase == EventPhase.Bubbling)
                  .map(_.target.mkString)
                  .toVector
      _ = targets.size shouldBe 2
      // Match the rendered id attributes, rather than depending on tree order.
      revealTarget = targets
                       .find(target => delivered.exists(_.contains(s""""$target",0,"id","reveal-setup"""")))
                       .getOrElse(fail("Missing rendered reveal form"))
      confirmTarget = targets
                        .find(target => delivered.exists(_.contains(s""""$target",0,"id","confirm-setup"""")))
                        .getOrElse(fail("Missing rendered confirm form"))
      _      = revealTarget should not be confirmTarget
      _     <- incoming.enqueue(submit(connection, deliveredRevision, revealTarget, Vector.empty))
      shown <- until(app.frontend, "[22,")
      acknowledgment = shown.last match {
                         case sensitive(boundConnection, presentation, name) =>
                           boundConnection shouldBe connection
                           s"$boundConnection:$presentation:$name:ok"
                         case _ => fail("Missing synthetic disclosure binding")
                       }
      _ = completionInvoked.get() shouldBe 0
      _ <- if (scenario == Scenario.QueuedModification)
             app.topLevelComponentInstance.browserAccess.transitionAsync { _ =>
               priorTransitionEntered.trySuccess(())
               releasePriorTransition.future
             }.flatMap(_ => priorTransitionEntered.future)
           else Future.unit
      _ <- incoming.enqueue(frame(8, acknowledgment))
      // This counter is published only after the actual form binding finishes,
      // including its completion callback and state-update interpretation.
      inspected <- until(app.frontend, counter(revealTarget))
      _          = inspected.last.stripSuffix("]").trim should endWith("1")
      _          = completionInvoked.get() shouldBe 1
      _ <- scenario match {
             case Scenario.IdentityModification | Scenario.StateModification =>
               // Publication, not delivery, invalidates the old revision. A patch
               // can precede or follow the completion counter on independent queues.
               val published = revision(inspected) match {
                 case Some(value) => Future.successful(value)
                 case None        => until(app.frontend, "[20,").map(frames => revision(frames).get)
               }
               published.map(value => value should be > deliveredRevision)
             case Scenario.QueuedModification =>
               // Normal transitions complete enqueueing. The typed reducer must not
               // execute or capture the old state while the earlier update waits.
               reducerInvoked.get() shouldBe 0
               releasePriorTransition.success("intervening")
               publishedQueuedResult(app.frontend).map(_ => reducerInvoked.get() shouldBe 1)
             case _ => Future.unit
           }
      state <- app.topLevelComponentInstance.browserAccess.state
      _ = state shouldBe (scenario match {
            case Scenario.StateModification  => "confirmed setup"
            case Scenario.QueuedModification => "intervening:completed"
            case _                           => "initial"
          })
      // Read state while the guard is still valid; subsequent state access is
      // also protected by the authority/ownership fence under test.
      _ = if (scenario == Scenario.RevokedAuthority) authorityValid.set(false)
      _ = if (scenario == Scenario.ReplacedOwner) owner.set(attachedNextOwner)
      // Inspected post-ack patches have deliberately not been delivered. The
      // second, distinct form therefore still submits the original revision.
      _ <-
        incoming.enqueue(submit(connection, deliveredRevision, confirmTarget, Vector(fieldName.value -> confirmation)))
      _ <- if (scenario == Scenario.NoUpdate) until(app.frontend, counter(confirmTarget)).map { completed =>
             completed.last.stripSuffix("]").trim should endWith("1")
             guardClosed.isCompleted shouldBe false
             confirmInvoked.get() shouldBe 1
           }
           else guardClosed.future.map(_ => confirmInvoked.get() shouldBe 0)
    } yield {
      revealInvoked.get() shouldBe 1
      completionInvoked.get() shouldBe 1
      rejected.get() shouldBe 0
    }

    bounded(workflow, 10L).transformWith { result =>
      releasePriorTransition.trySuccess("cleanup")
      val cleanup = appRef.get() match {
        case Some(app) => app.frontend.close().flatMap(_ => guardClosed.future)
        case None      => incoming.stream.cancel()
      }
      bounded(cleanup, 3L).transformWith { cleanupResult =>
        timer.shutdownNow()
        Future.fromTry(result.flatMap(value => cleanupResult.map(_ => value)))
      }
    }
  }

  private def attachedNextOwner = ViewOwnershipEpoch.initial.next.fold(error => fail(error.toString), identity)

  "A typed sensitive acknowledgement" should "admit the next delivered form after NoUpdate completes" in {
    exercise(Scenario.NoUpdate)
  }

  it should "reject the old delivered revision after explicit identity modification publishes" in {
    exercise(Scenario.IdentityModification)
  }

  it should "reject the old delivered revision after a state modification publishes" in {
    exercise(Scenario.StateModification)
  }

  it should "still deny revoked authority after NoUpdate completes" in {
    exercise(Scenario.RevokedAuthority)
  }

  it should "apply the queued typed reducer once to the state left by an earlier transition" in {
    exercise(Scenario.QueuedModification)
  }

  it should "still deny a replaced view owner after NoUpdate completes" in {
    exercise(Scenario.ReplacedOwner)
  }
}
