package spoonbill.server.internal.services

import avocet.Id
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import spoonbill.Qsid
import spoonbill.effect.{Effect, Queue}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{SessionAccessControl, SessionAccessDenied, SessionGuard, SpoonbillServiceConfig, StateLoader}
import spoonbill.state.{DeviceId, SessionId, StateManager, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

class GuardedRestoreCleanupSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private val qsid                            = Qsid("guarded-device", "guarded-view")
  private val request                         = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())

  /**
   * Models storage with one active slot and an independently retained snapshot.
   */
  private class PromotingStorage extends StateStorage[Future, String] {
    val active           = new AtomicInteger(0)
    val acquired         = new AtomicInteger(0)
    val removed          = new AtomicInteger(0)
    private val snapshot = StateManager.cached[Future](Map(Id.TopLevel -> "protected snapshot"))

    def exists(deviceId: DeviceId, sessionId: SessionId): Future[Boolean] = Future.successful(true)
    def create(deviceId: DeviceId, sessionId: SessionId, state: String): Future[StateManager[Future]] =
      Future.failed(new IllegalStateException("This fixture already has a snapshot"))
    def get(deviceId: DeviceId, sessionId: SessionId): Future[StateManager[Future]] = effect.delay {
      if (!active.compareAndSet(0, 1)) throw new IllegalStateException("Active storage capacity exhausted")
      acquired.incrementAndGet()
      snapshot
    }
    def remove(deviceId: DeviceId, sessionId: SessionId): Unit = {
      active.set(0)
      removed.incrementAndGet()
      ()
    }
  }

  private def service(storage: PromotingStorage, control: SessionAccessControl[Future, String]) = {
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("public"),
      stateStorage = storage,
      sessionAccessControl = Some(control)
    )(executionContext)
    new SessionsService[Future, String, Any](config, new PageService[Future, String, Any](config))
  }

  "Guarded restoration cleanup" should "release a promoted snapshot after denial before wrapper construction on every retry" in {
    val storage = new PromotingStorage
    val closed  = new AtomicInteger(0)
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
      def open(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): Future[SessionGuard[Future, String]] =
        Future.successful(new SessionGuard[Future, String] {
          def authorize(state: String): Future[Unit] = Future.failed(new SessionAccessDenied)
          def connected(state: String): Future[String] =
            Future.failed(new IllegalStateException("Denied state must not connect"))
          def close(): Future[Unit] = effect.delay { closed.incrementAndGet(); () }
        })
    }
    val sessions = service(storage, control)
    def deniedAttempt(): Future[Unit] =
      sessions
        .createAppIfNeeded(qsid, request, Queue[Future, String]().stream)
        .map(_ => fail("Expected restored-state denial"))
        .recover { case _: SessionAccessDenied => () }

    for {
      _      <- deniedAttempt()
      first  <- sessions.getApp(qsid)
      _      <- deniedAttempt()
      second <- sessions.getApp(qsid)
    } yield {
      first shouldBe None
      second shouldBe None
      storage.acquired.get() shouldBe 2
      storage.removed.get() shouldBe 2
      storage.active.get() shouldBe 0
      closed.get() shouldBe 2
    }
  }

  it should "leave unacquired storage untouched when opening the guard fails" in {
    val storage = new PromotingStorage
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
      def open(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): Future[SessionGuard[Future, String]] =
        Future.failed(new SessionAccessDenied)
    }
    service(storage, control)
      .createAppIfNeeded(qsid, request, Queue[Future, String]().stream)
      .map(_ => fail("Expected open denial"))
      .recover { case _: SessionAccessDenied =>
        storage.acquired.get() shouldBe 0
        storage.removed.get() shouldBe 0
      }
  }

  private def deniedRestoration(storage: PromotingStorage, opened: AtomicInteger, closed: AtomicInteger) = {
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
      def open(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): Future[SessionGuard[Future, String]] = {
        opened.incrementAndGet()
        Future.successful(new SessionGuard[Future, String] {
          def authorize(state: String): Future[Unit] = Future.failed(new SessionAccessDenied)
          def connected(state: String): Future[String] = Future.failed(new SessionAccessDenied)
          def close(): Future[Unit] = effect.delay { closed.incrementAndGet(); () }
        })
      }
    }
    service(storage, control)
  }

  it should "release local attachment and retry storage cleanup that threw after releasing its resource" in {
    val failFirst = new AtomicBoolean(true)
    val storage = new PromotingStorage {
      override def remove(deviceId: DeviceId, sessionId: SessionId): Unit = {
        super.remove(deviceId, sessionId)
        if (failFirst.compareAndSet(true, false)) throw new IllegalStateException("Injected retirement failure")
      }
    }
    val opened = new AtomicInteger(0)
    val closed = new AtomicInteger(0)
    val sessions = deniedRestoration(storage, opened, closed)
    def deniedAttempt(): Future[Throwable] =
      sessions.createAppIfNeeded(qsid, request, Queue[Future, String]().stream).failed

    for {
      first <- deniedAttempt()
      _ = {
        first shouldBe a[SessionAccessDenied]
        storage.active.get() shouldBe 0
        closed.get() shouldBe 1
      }
      second <- deniedAttempt()
      app <- sessions.getApp(qsid)
    } yield {
      second shouldBe a[SessionAccessDenied]
      opened.get() shouldBe 2
      storage.acquired.get() shouldBe 2
      storage.active.get() shouldBe 0
      closed.get() shouldBe 2
      app shouldBe None
    }
  }

  it should "retry an unreleased storage resource before opening a replacement guard" in {
    val unavailable = new AtomicBoolean(true)
    val storage = new PromotingStorage {
      override def remove(deviceId: DeviceId, sessionId: SessionId): Unit = {
        if (unavailable.get()) throw new IllegalStateException("Storage retirement unavailable")
        super.remove(deviceId, sessionId)
      }
    }
    val opened = new AtomicInteger(0)
    val closed = new AtomicInteger(0)
    val sessions = deniedRestoration(storage, opened, closed)
    def deniedAttempt(): Future[Throwable] =
      sessions.createAppIfNeeded(qsid, request, Queue[Future, String]().stream).failed

    for {
      first <- deniedAttempt()
      _ = {
        first shouldBe a[SessionAccessDenied]
        storage.active.get() shouldBe 1
      }
      failedRetry <- deniedAttempt()
      _ = {
        failedRetry shouldBe a[IllegalStateException]
        opened.get() shouldBe 1 // The storage failure must not allow a replacement to open yet.
        storage.acquired.get() shouldBe 1
        unavailable.set(false)
      }
      restoredRetry <- deniedAttempt()
      app <- sessions.getApp(qsid)
    } yield {
      restoredRetry shouldBe a[SessionAccessDenied]
      opened.get() shouldBe 2
      storage.acquired.get() shouldBe 2
      storage.active.get() shouldBe 0
      closed.get() shouldBe 2
      app shouldBe None
    }
  }
}
