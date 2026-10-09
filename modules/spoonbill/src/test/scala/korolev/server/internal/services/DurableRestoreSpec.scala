package spoonbill.server.internal.services

import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.Qsid
import spoonbill.effect.{Effect, Queue}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.server.*
import spoonbill.snapshot.*
import spoonbill.state.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

class DurableRestoreSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private case class State(subject: String, counter: Int) extends Serializable
  private val qsid = Qsid("device", "view")
  private val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())

  "Durable restoration" should "bootstrap an absent local view from fresh authority and a full stamped render" in {
    val closed = Promise[Unit]()
    val store = new ViewSnapshotStore[Future, Int] {
      val ownerEpoch = ViewOwnershipEpoch.initial
      def load() = Future.successful(Right(SnapshotLoad.Restored(ViewRevision.initial, 8)))
      def save(expected: ViewRevision, value: Int) = Future.successful(expected.next.left.map(_ => ViewSnapshotError.RevisionExhausted))
      def reset(expected: ViewRevision, value: Int) = Future.failed(new AssertionError("No reset expected"))
    }
    val control = new SessionAccessControl[Future, State] {
      def authorizeHttp(request: Request.Head, state: State) = Future.unit
      override def resume(id: Qsid, request: Request.Head, connection: ConnectionId) = Some(open(id, request, connection))
      def open(id: Qsid, request: Request.Head, connection: ConnectionId) = Future.successful(new SessionGuard[Future, State] {
        override val viewSnapshots = Some(ViewSnapshotSession.projected[Future, State, Int](store, _.counter,
          (fresh, count) => fresh.copy(counter = count)))
        def authorize(state: State) = if (state.subject == "fresh") Future.unit else Future.failed(new SessionAccessDenied)
        def connected(state: State) = Future.successful(State("fresh", 0))
        def close() = Future.successful { closed.trySuccess(()); () }
      })
    }
    val config = SpoonbillServiceConfig[Future, State, Any](
      stateLoader = StateLoader.default(State("anonymous", 0)),
      stateStorage = new StateStorage[Future, State] {
        def exists(device: DeviceId, session: SessionId) = Future.successful(false)
        def create(device: DeviceId, session: SessionId, value: State) = Future.failed(new AssertionError("No legacy state persistence"))
        def get(device: DeviceId, session: SessionId) = Future.failed(new AssertionError("No legacy restore"))
        def remove(device: DeviceId, session: SessionId): Unit = fail("No local bootstrap was acquired")
      },
      document = state => Html(body(div(state.subject), div(state.counter.toString))),
      sessionAccessControl = Some(control)
    )(executionContext)
    val service = new SessionsService(config, new PageService(config))
    val incoming = Queue[Future, String]()
    def initializationFrames(frontend: spoonbill.internal.Frontend[Future], remaining: Int = 8,
      seen: Vector[String] = Vector.empty): Future[Vector[String]] =
      if (remaining == 0) Future.failed(new AssertionError("Application readiness was not published"))
      else frontend.outgoingMessages.pull().flatMap {
        case Some(frame) if frame.startsWith("[21,") => Future.successful(seen :+ frame)
        case Some(frame) => initializationFrames(frontend, remaining - 1, seen :+ frame)
        case None => Future.failed(new AssertionError("Application closed before readiness"))
      }
    for {
      _ <- service.createAppIfNeeded(qsid, request, incoming.stream)
      appOption <- service.getApp(qsid)
      app = appOption.getOrElse(fail("No restored app"))
      // Development mode may send reload-CSS alongside the reset-counters
      // control. Read through application readiness rather than assuming the
      // DOM baseline is always the second transport frame.
      frames <- initializationFrames(app.frontend)
      _ <- app.frontend.close()
      _ <- closed.future
    } yield {
      frames.exists(_.startsWith("[17")) shouldBe true
      val baseline = frames.find(_.startsWith("[19")).getOrElse(fail("No full baseline"))
      baseline should include("fresh")
      baseline should include("\"8\"")
      baseline should not include "anonymous"
      baseline should not include "[1]"
    }
  }

  it should "keep default guarded bootstrap transient even when development mode is enabled" in {
    implicit val serializer: StateSerializer[String] = new StateSerializer[String] {
      def serialize(value: String): Array[Byte] = throw new AssertionError("Guarded bootstrap must not serialize arbitrary state")
    }
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String) = Future.unit
      def open(id: Qsid, request: Request.Head, connection: ConnectionId) = Future.failed(new AssertionError("No socket in this test"))
    }
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("transient-bootstrap"), sessionAccessControl = Some(control)
    )(executionContext)
    val service = new SessionsService(config, new PageService(config))
    service.initAppState(qsid, request).map(_ shouldBe "transient-bootstrap")
  }
}
