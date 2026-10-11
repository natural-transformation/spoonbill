package spoonbill.server.internal.services

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import spoonbill.Qsid
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.server.*
import spoonbill.snapshot.{ViewSnapshotError, ViewSnapshotException, ViewSnapshotSession}
import spoonbill.state.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

class SnapshotSetupDiagnosticSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  private final class CapturingReporter(throwOnError: Boolean) extends Reporter {
    val errors = new AtomicReference(Vector.empty[(String, Throwable)])
    def error(message: String, cause: Throwable): Unit = {
      errors.updateAndGet(_ :+ (message -> cause))
      if (throwOnError) throw new IllegalStateException("Synthetic reporter failure")
    }
    def error(message: String): Unit = error(message, new IllegalStateException("No diagnostic code"))
    def warning(message: String, cause: Throwable): Unit = ()
    def warning(message: String): Unit = ()
    def info(message: String): Unit = ()
    def debug(message: String): Unit = ()
    def debug(message: String, arg1: Any): Unit = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  }

  for (throwOnError <- Vector(false, true)) {
    "Snapshot setup diagnostics" should
      s"report one redacted rejection after cleanup and retain the original failure (reporter throws: $throwOnError)" in {
      val privateValue = "synthetic-private-projection-and-credential"
      val rejected = new ViewSnapshotException(ViewSnapshotError.SchemaIncompatible)
      rejected.initCause(new IllegalArgumentException(privateValue))
      rejected.addSuppressed(new IllegalArgumentException(privateValue))
      val closed = new AtomicInteger()
      val removed = new AtomicInteger()
      val reporter = new CapturingReporter(throwOnError)
      val qsid = Qsid(privateValue, "synthetic-view")
      val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
      val storage = new StateStorage[Future, String] {
        def exists(device: DeviceId, session: SessionId) = Future.successful(true)
        def create(device: DeviceId, session: SessionId, value: String) =
          Future.failed(new AssertionError("No bootstrap creation expected"))
        def get(device: DeviceId, session: SessionId) =
          Future.failed(new AssertionError("Durable projection must not restore arbitrary local state"))
        def remove(device: DeviceId, session: SessionId): Unit = { removed.incrementAndGet(); () }
      }
      val control = new SessionAccessControl[Future, String] {
        def authorizeHttp(request: Request.Head, state: String) = Future.unit
        def open(id: Qsid, request: Request.Head, connection: ConnectionId) = Future.successful(new SessionGuard[Future, String] {
          override val viewSnapshots = Some(new ViewSnapshotSession[Future, String] {
            val ownerEpoch = ViewOwnershipEpoch.initial
            def initialize(fresh: String) = Future.failed(rejected)
            def commit(candidate: String) = Future.failed(new AssertionError("Rejected projection cannot commit"))
          })
          def authorize(state: String) = Future.unit
          def connected(state: String) = Future.successful(state)
          def close() = Future.successful { closed.incrementAndGet(); () }
        })
      }
      val config = SpoonbillServiceConfig[Future, String, Any](
        stateLoader = StateLoader.default(privateValue),
        stateStorage = storage,
        sessionAccessControl = Some(control),
        reporter = reporter
      )(executionContext)
      val service = new SessionsService(config, new PageService(config))
      def attempt() = service.createAppIfNeeded(qsid, request, Queue[Future, String]().stream).failed

      for {
        first <- attempt()
        second <- attempt()
        app <- service.getApp(qsid)
      } yield {
        first should be theSameInstanceAs rejected
        second should be theSameInstanceAs rejected
        app shouldBe None
        closed.get() shouldBe 2
        removed.get() shouldBe 2
        val diagnostics = reporter.errors.get()
        diagnostics.size shouldBe 2
        diagnostics.foreach { case (message, cause) =>
          message shouldBe "Guarded view snapshot setup rejected"
          message should not include privateValue
          cause.asInstanceOf[ViewSnapshotException].error shouldBe ViewSnapshotError.SchemaIncompatible
          cause should not be theSameInstanceAs(rejected)
          cause.getCause shouldBe null
          cause.getSuppressed shouldBe empty
          cause.toString should not include privateValue
        }
        succeed
      }
    }
  }
}
