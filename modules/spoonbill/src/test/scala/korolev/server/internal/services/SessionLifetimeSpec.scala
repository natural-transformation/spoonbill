package spoonbill.server.internal.services

import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.{Extension, Qsid}
import spoonbill.effect.{Effect, Queue}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{ApplicationStateInitialization, SessionAccessControl, SessionAccessDenied, SessionGuard, SpoonbillServiceConfig, StateLoader}
import spoonbill.state.{StateDeserializer, StateManager, StateSerializer, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

class SessionLifetimeSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  for (holdWrite <- List(true, false)) {
    "Guarded cleanup" should s"retain the local attachment while ${if (holdWrite) "an admitted write drains" else "guard close is pending"}, without joining repeated cleanup" in {
      val entered = Promise[Unit]()
      val held = Promise[Unit]()
      var blockWrites = false
      var opens = 0
      var order = Vector.empty[String]
      val underlying = StateManager.cached[Future](Map(Id.TopLevel -> 0))
      val manager = new StateManager[Future] {
        def read[T: StateDeserializer](id: Id): Future[Option[T]] = underlying.read[T](id)
        def write[T: StateSerializer](id: Id, value: T): Future[Unit] = {
          val ready = if (blockWrites) { entered.trySuccess(()); held.future } else Future.unit
          ready.flatMap(_ => underlying.write(id, value))
        }
        def delete(id: Id): Future[Unit] = underlying.delete(id)
        def snapshot: Future[StateManager.Snapshot] = underlying.snapshot
      }
      val storage = new StateStorage[Future, Int] {
        def exists(device: String, session: String): Future[Boolean] = Future.successful(true)
        def create(device: String, session: String, value: Int): Future[StateManager[Future]] = Future.successful(manager)
        def get(device: String, session: String): Future[StateManager[Future]] = Future.successful(manager)
        def remove(device: String, session: String): Unit = { order :+= "storage" }
      }
      val control = new SessionAccessControl[Future, Int] {
        def authorizeHttp(request: Request.Head, state: Int): Future[Unit] = Future.unit
        def open(qsid: Qsid, request: Request.Head, connection: ConnectionId): Future[SessionGuard[Future, Int]] = {
          opens += 1
          Future.successful(new SessionGuard[Future, Int] {
            val applicationStateInitialization: ApplicationStateInitialization[Future] = ApplicationStateInitialization.Retained[Future]()
            def authorize(state: Int): Future[Unit] = Future.unit
            def connected(state: Int): Future[Int] = Future.successful(state)
            def close(): Future[Unit] = {
              order :+= "guard"
              if (holdWrite) Future.unit else { entered.trySuccess(()); held.future }
            }
          })
        }
      }
      val config = SpoonbillServiceConfig[Future, Int, Unit](
        stateLoader = StateLoader.default(0),
        stateStorage = storage,
        document = state => Html(body(div(state.toString))),
        sessionAccessControl = Some(control),
        extensions = List(Extension.pure[Future, Int, Unit]("record")(_ =>
          Extension.Handlers[Future, Int, Unit](onDestroy = _ => { order :+= "extension"; Future.unit })))
      )
      val service = new SessionsService(config, new PageService(config))
      val qsid = Qsid("lifetime", if (holdWrite) "write" else "guard")
      val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
      val incoming = Queue[Future, String]()
      val attached = await(service.createAppIfNeeded(qsid, request, incoming.stream)).asInstanceOf[SessionStart.Attached[Future]]
      val app = await(service.getApp(qsid)).get
      blockWrites = holdWrite
      val write = if (holdWrite) app.topLevelComponentInstance.browserAccess.transitionForce(_ + 1) else Future.unit
      if (holdWrite) await(entered.future)
      val retiring = attached.release()
      try {
        await(entered.future)
        retiring.isCompleted shouldBe false
        await(attached.release())
        retiring.isCompleted shouldBe false
        intercept[SessionAccessDenied](await(service.createAppIfNeeded(qsid, request, Queue[Future, String]().stream)))
        opens shouldBe 1
        order shouldBe (if (holdWrite) Vector.empty else Vector("guard"))
        held.success(())
        await(retiring)
        // The already-admitted write can finish even though later presentation is denied.
        await(write.recover { case _: SessionAccessDenied => () })
        order shouldBe Vector("guard", "extension", "storage")
        await(service.getApp(qsid)) shouldBe None
      } finally {
        held.trySuccess(())
        await(attached.release())
      }
    }
  }
}
