package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import avocet.events.EventPhase
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*
import spoonbill.{Context, Extension, Qsid}
import spoonbill.effect.{Effect, Queue}
import spoonbill.internal.Frontend
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.server.*
import spoonbill.snapshot.ViewSnapshotSession
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * What extensions cost a guarded session.
 *
 * Each guard observation can be a full provider transaction, so their number
 * sets guarded latency; see GuardedEventObservationSpec. These cases pin three
 * properties:
 *
 *   - A view without extensions pays nothing for the extension runtime.
 *   - With extensions, a delivery costs one observation: one per delivered
 *     state and one per message, whatever the number of extensions.
 *   - Rendering does not wait for an extension callback.
 *
 * The numbers without extensions are those of Spoonbill 10.0.1. The numbers
 * with extensions differ from 10.0.1 in one place: a session that reconstructs
 * its state now delivers the attach state, which is one more observation per
 * attach.
 */
class ExtensionCostSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  private final case class Cost(attach: List[String], click: List[String], publish: List[String])

  /**
   * Attaches a view, clicks once and publishes one message. It returns the
   * guard observations of each step.
   *
   * @param stall
   *   the first extension never completes its `onState` call for the clicked
   *   state. The publish step is then skipped, because the lane is stalled.
   */
  private def measure(extensionCount: Int, reconstructed: Boolean, stall: Boolean = false): Future[Cost] = {
    val observations = new ConcurrentLinkedQueue[String]()
    def observe(states: List[String]): Future[Unit] = {
      observations.add(states.mkString("[", ",", "]"))
      Future.unit
    }
    val sawClicked = Promise[Unit]()
    val sawMessage = Promise[Unit]()
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String) = Future.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId) =
        Future.successful(new SessionGuard[Future, String] {
          val applicationStateInitialization =
            if (reconstructed) ApplicationStateInitialization.Reconstructed[Future](() => Future.unit)
            else ApplicationStateInitialization.Retained[Future]()
          // Both entry points count as one observation each.
          def authorize(state: String) = observe(List(state))
          override private[spoonbill] def authorizeEach(states: List[String])(using Effect[Future]) =
            observe(states)
          def connected(state: String) = Future.successful(state)
          def close()                  = Future.unit
        })
    }
    val context = Context[Future, String, Any]
    import context.*
    val extensions = List.tabulate(extensionCount) { index =>
      Extension.pure[Future, String, Any](s"extension-$index") { _ =>
        Extension.Handlers[Future, String, Any](
          onState = state =>
            if (index == 0 && state == "clicked") {
              sawClicked.trySuccess(())
              if (stall) Future.never else Future.unit
            } else Future.unit,
          onMessage = _ => { if (index == 0) sawMessage.trySuccess(()); Future.unit }
        )
      }
    }
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("initial"),
      document =
        state => Html(body(p(state), button("Act", event("click")(access => access.transition(_ => "clicked"))))),
      extensions = extensions,
      sessionAccessControl = Some(control)
    )(executionContext)
    val service  = new SessionsService(config, new PageService(config))
    val qsid     = Qsid("cost-device", UUID.randomUUID().toString)
    val request  = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incoming = Queue[Future, String]()
    def until(frontend: Frontend[Future], prefix: String, remaining: Int = 24): Future[Unit] =
      if (remaining == 0) Future.failed(new AssertionError(s"The frame $prefix was not published"))
      else
        frontend.outgoingMessages.pull().flatMap {
          case Some(frame) if frame.startsWith(prefix) => Future.unit
          case Some(_)                                 => until(frontend, prefix, remaining - 1)
          case None                                    => Future.failed(new AssertionError("Connection closed"))
        }
    def taken(): List[String] = {
      val result = observations.asScala.toList
      observations.clear()
      result
    }
    // Lets the queued callbacks of the serial execution context run.
    def settle(rounds: Int = 20): Future[Unit] =
      if (rounds == 0) Future.unit else Future(()).flatMap(_ => settle(rounds - 1))
    val delivered = extensionCount > 0

    for {
      _     <- service.initAppState(qsid, request)
      _      = taken()
      _     <- service.createAppIfNeeded(qsid, request, incoming.stream)
      app   <- service.getApp(qsid).map(_.getOrElse(fail("Missing app")))
      _     <- until(app.frontend, "[21,")
      _     <- settle()
      attach = taken()
      target = app.topLevelComponentInstance.allEventHandlers.keys.collectFirst {
                 case id if id.`type` == "click" && id.phase == EventPhase.Bubbling => id.target.mkString
               }
                 .getOrElse(fail("Missing click handler"))
      _ <- incoming.enqueue(s"""[0,"0:$target:click"]""")
      // The patch and the event-counter update arrive, whatever the extensions do.
      _      <- until(app.frontend, s"""[0,"$target","click",""")
      _      <- if (delivered) sawClicked.future else Future.unit
      _      <- settle()
      click   = taken()
      _      <- if (stall) Future.unit else app.topLevelComponentInstance.browserAccess.publish("message")
      _      <- if (delivered && !stall) sawMessage.future else Future.unit
      _      <- settle()
      publish = taken()
      _      <- app.frontend.close()
    } yield Cost(attach, click, publish)
  }

  /**
   * The guard observations of an attach to a session with snapshots. Such a
   * view speaks the recovery protocol, so this case stops at readiness.
   */
  private def measureSnapshotAttach(extensionCount: Int): Future[List[String]] = {
    val observations = new ConcurrentLinkedQueue[String]()
    def observe(states: List[String]): Future[Unit] = {
      observations.add(states.mkString("[", ",", "]"))
      Future.unit
    }
    val delivered = Promise[Unit]()
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String) = Future.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId) =
        Future.successful(new SessionGuard[Future, String] {
          val applicationStateInitialization = ApplicationStateInitialization.Retained[Future]()
          override val viewSnapshots = Some(new ViewSnapshotSession[Future, String] {
            def ownerEpoch: ViewOwnershipEpoch            = ViewOwnershipEpoch.initial
            def initialize(fresh: String): Future[String] = Future.successful(fresh)
            def commit(candidate: String): Future[Unit]   = Future.unit
          })
          def authorize(state: String) = observe(List(state))
          override private[spoonbill] def authorizeEach(states: List[String])(using Effect[Future]) =
            observe(states)
          def connected(state: String) = Future.successful(state)
          def close()                  = Future.unit
        })
    }
    val extensions = List.tabulate(extensionCount) { index =>
      Extension.pure[Future, String, Any](s"extension-$index") { _ =>
        Extension.Handlers[Future, String, Any](onState = _ => { delivered.trySuccess(()); Future.unit })
      }
    }
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("initial"),
      document = state => Html(body(p(state))),
      extensions = extensions,
      sessionAccessControl = Some(control)
    )(executionContext)
    val service = new SessionsService(config, new PageService(config))
    val qsid    = Qsid("cost-device", UUID.randomUUID().toString)
    val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    def settle(rounds: Int = 20): Future[Unit] =
      if (rounds == 0) Future.unit else Future(()).flatMap(_ => settle(rounds - 1))
    for {
      _     <- service.initAppState(qsid, request)
      _      = observations.clear()
      _     <- service.createAppIfNeeded(qsid, request, Queue[Future, String]().stream)
      app   <- service.getApp(qsid).map(_.getOrElse(fail("Missing app")))
      _     <- if (extensionCount > 0) delivered.future else Future.unit
      _     <- settle()
      attach = observations.asScala.toList
      _     <- app.frontend.close()
    } yield attach
  }

  // The click of GuardedEventObservationSpec: five boundaries, five observations.
  private val clickWithoutExtensions =
    List("[initial]", "[initial]", "[initial,clicked]", "[clicked]", "[clicked,clicked]")
  // One more for the delivery: the written and the current presentation together.
  private val clickWithExtensions = clickWithoutExtensions :+ "[clicked,clicked]"

  "A guarded view without extensions" should "pay nothing for the extension runtime" in {
    for {
      retained      <- measure(extensionCount = 0, reconstructed = false)
      reconstructed <- measure(extensionCount = 0, reconstructed = true)
    } yield {
      retained.attach.size shouldBe 10
      reconstructed.attach.size shouldBe 8
      retained.click shouldBe clickWithoutExtensions
      reconstructed.click shouldBe clickWithoutExtensions
      // Publishing is one interaction check. Nobody receives the message.
      retained.publish shouldBe List("[clicked]")
      reconstructed.publish shouldBe List("[clicked]")
    }
  }

  "A guarded view with extensions" should "pay one observation per delivery" in {
    for {
      retained      <- measure(extensionCount = 1, reconstructed = false)
      reconstructed <- measure(extensionCount = 1, reconstructed = true)
    } yield {
      // The attach state is one delivery. A retained session already paid it
      // in 10.0.1, for the delivery of its connected state.
      retained.attach.size shouldBe 11
      reconstructed.attach.size shouldBe 9
      retained.click.sorted shouldBe clickWithExtensions.sorted
      reconstructed.click.sorted shouldBe clickWithExtensions.sorted
      // The publisher's interaction check, and the check before the delivery.
      retained.publish shouldBe List("[clicked]", "[clicked]")
      reconstructed.publish shouldBe List("[clicked]", "[clicked]")
    }
  }

  it should "pay one observation for the attach state of a session with snapshots" in {
    for {
      without <- measureSnapshotAttach(extensionCount = 0)
      one     <- measureSnapshotAttach(extensionCount = 1)
      three   <- measureSnapshotAttach(extensionCount = 3)
    } yield {
      // 10.0.1 delivered no attach state here, so this delivery is new.
      without.size shouldBe 6
      one.size shouldBe 7
      three.size shouldBe 7
    }
  }

  it should "pay the same for three extensions as for one" in {
    for {
      one   <- measure(extensionCount = 1, reconstructed = false)
      three <- measure(extensionCount = 3, reconstructed = false)
    } yield {
      three.attach.size shouldBe one.attach.size
      three.click.sorted shouldBe one.click.sorted
      three.publish shouldBe one.publish
    }
  }

  "Rendering" should "not wait for an extension callback that never completes" in {
    // measure fails when the patch or the event-counter update is not published.
    measure(extensionCount = 2, reconstructed = false, stall = true).map { cost =>
      cost.click.sorted shouldBe clickWithExtensions.sorted
    }
  }
}
