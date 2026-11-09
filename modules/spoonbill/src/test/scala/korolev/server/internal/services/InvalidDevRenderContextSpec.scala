package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Queue, Stream}
import spoonbill.internal.DevMode
import spoonbill.internal.TimerTestSupport.Reports
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{ApplicationStateInitialization, SessionAccessControl, SessionGuard, SpoonbillServiceConfig, StateLoader, WebSocketResponse}
import spoonbill.state.{StateManager, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

final class InvalidDevRenderContextSpec extends AnyFlatSpec with Matchers {

  private given Effect[Future] = Effect.futureEffect
  private def await[A](operation: Future[A]): A = Await.result(operation, 5.seconds)
  // Long enough for the versioned header, but with incompatible legacy magic.
  private val corruptBytes = Array.fill[Byte](24)(0)
  private val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())

  private def withDevMode(test: scala.collection.mutable.Buffer[File] => Unit): Unit = {
    val previous = sys.props.get("spoonbill.dev")
    val files = scala.collection.mutable.ArrayBuffer.empty[File]
    sys.props.update("spoonbill.dev", "true")
    try test(files)
    finally {
      files.foreach(file => Files.deleteIfExists(file.toPath))
      previous match {
        case Some(value) => sys.props.update("spoonbill.dev", value)
        case None        => sys.props.remove("spoonbill.dev")
      }
    }
  }

  private def savedFile(qsid: Qsid, files: scala.collection.mutable.Buffer[File]): File = {
    val file = new DevMode.ForRenderContext(qsid.toString).file
    files += file
    files += new File(file.getPath + ".invalid")
    file
  }

  private def configuration(
    control: Option[SessionAccessControl[Future, String]] = None,
    storage: StateStorage[Future, String] = StateStorage.ephemeral[Future, String]()
  ) =
    SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("initial"),
      stateStorage = storage,
      document = state => Html(body(div(state))),
      reporter = new Reports,
      sessionAccessControl = control
    )

  private final class PendingInput extends Stream[Future, Bytes] {
    private val next = Promise[Option[Bytes]]()
    val canceled = Promise[Unit]()
    def pull(): Future[Option[Bytes]] = next.future
    def cancel(): Future[Unit] = Effect[Future].delay {
      next.trySuccess(None)
      canceled.trySuccess(())
      ()
    }
  }

  private final class DelayedFirstRead extends StateStorage[Future, String] {
    private val underlying = StateStorage.ephemeral[Future, String]()
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val reads = new AtomicInteger()

    def exists(deviceId: String, sessionId: String): Future[Boolean] = underlying.exists(deviceId, sessionId)
    def create(deviceId: String, sessionId: String, state: String): Future[StateManager[Future]] =
      underlying.create(deviceId, sessionId, state)
    def remove(deviceId: String, sessionId: String): Unit = underlying.remove(deviceId, sessionId)
    def get(deviceId: String, sessionId: String): Future[StateManager[Future]] =
      if (reads.incrementAndGet() == 1) {
        entered.success(())
        release.future.flatMap(_ => underlying.get(deviceId, sessionId))
      } else underlying.get(deviceId, sessionId)
  }

  for (stateExists <- Seq(true, false)) {
    "An invalid dev render context" should s"reload every connection when prior state exists=$stateExists" in withDevMode { files =>
      val storage = new DelayedFirstRead
      val config = configuration(storage = storage)
      def newService() = new SessionsService(config, new PageService(config))
      val sessions = newService()
      val qsid = Qsid("invalid-dev", UUID.randomUUID().toString)
      val file = savedFile(qsid, files)
      val retained = new File(file.getPath + ".invalid")
      Files.write(file.toPath, corruptBytes)
      if (stateExists) await(sessions.initAppState(qsid, request))

      def expectReload(service: SessionsService[Future, String, Any]): Future[Unit] = {
        val input = new PendingInput
        val messaging = new MessagingService[Future](
          reporter = config.reporter,
          commonService = new CommonService[Future],
          sessionsService = service,
          compressionSupport = None,
          orphanTopicTimeout = 1.second
        )
        for {
          response <- messaging.webSocketMessaging(qsid, request, input, Seq("json"))
          _ <- response match {
                 case WebSocketResponse.SendThenClose(output, protocol, release) =>
                   for {
                     first <- output.pull()
                     end <- output.pull()
                     _ = protocol shouldBe "json"
                     _ = first.map(_.asUtf8String) shouldBe Some("[1]")
                     _ = end shouldBe None
                     // The rejected session releases input before transport cleanup.
                     _ <- input.canceled.future
                     _ <- release()
                   } yield ()
                 case WebSocketResponse.Duplex(_, _, release) =>
                   release().map(_ => fail("Invalid saved DOM must request a full page reload, not attach a fresh baseline"))
               }
          app <- service.getApp(qsid)
        } yield {
          app shouldBe None
          ()
        }
      }

      // Concurrent waiters and later reconnects must all settle, including the
      // AsyncTable cell whose application producer failed during construction.
      val pending = Vector.fill(4)(expectReload(sessions))
      try {
        await(storage.entered.future)
        pending.foreach(_.isCompleted shouldBe false)
        storage.reads.get() shouldBe 1
      } finally storage.release.trySuccess(())
      await(Future.sequence(pending))
      await(expectReload(sessions))
      file.exists() shouldBe false
      Files.readAllBytes(retained.toPath).toSeq shouldBe corruptBytes.toSeq
      await(expectReload(newService()))
      config.reporter.asInstanceOf[Reports].errors.exists { case (message, error) =>
        message.contains(file.getPath) && message.toLowerCase.contains("full page reload") &&
        error.isInstanceOf[DevMode.InvalidSavedRenderContext]
      } shouldBe true

      // HTTP navigation allocates a new session and establishes its own DOM.
      val fresh = await(sessions.initSession(request))
      fresh should not be qsid
      savedFile(fresh, files)
      await(sessions.initAppState(fresh, request))
      val attached = await(sessions.createAppIfNeeded(fresh, request, Queue[Future, String]().stream))
      attached match {
        case SessionStart.Attached(_, release) =>
          try await(sessions.getApp(fresh)).isDefined shouldBe true
          finally await(release())
        case SessionStart.Reload() => fail("A fresh HTTP session must initialize normally")
      }
    }
  }

  for ((description, bytes) <- Seq("incompatible" -> corruptBytes, "truncated" -> Array[Byte](0, 1, 2))) {
    it should s"retain $description bytes and explain how to recover" in withDevMode { files =>
      val qsid = Qsid("invalid-dev-diagnostic", UUID.randomUUID().toString)
      val file = savedFile(qsid, files)
      val retained = new File(file.getPath + ".invalid")
      Files.write(file.toPath, bytes)
      val error = intercept[DevMode.InvalidSavedRenderContext] {
        new DevMode.ForRenderContext(qsid.toString).restoreRenderContext[Nothing]()
      }
      error.getMessage should include(file.getPath)
      error.getMessage should include(retained.getPath)
      error.getMessage.toLowerCase should include("reload")
      error.getCause should not be null
      file.exists() shouldBe false
      Files.readAllBytes(retained.toPath).toSeq shouldBe bytes.toSeq

      intercept[DevMode.InvalidSavedRenderContext] {
        new DevMode.ForRenderContext(qsid.toString).restoreRenderContext[Nothing]()
      }
      Files.readAllBytes(retained.toPath).toSeq shouldBe bytes.toSeq
    }
  }

  it should "leave legacy saved buffers untouched for guarded sessions" in withDevMode { files =>
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
      def open(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): Future[SessionGuard[Future, String]] =
        Future.successful(new SessionGuard[Future, String] {
          def applicationStateInitialization = ApplicationStateInitialization.Retained[Future]()
          def authorize(state: String): Future[Unit] = Future.unit
          def connected(state: String): Future[String] = Future.successful(state)
          def close(): Future[Unit] = Future.unit
        })
    }
    val config = configuration(Some(control))
    val sessions = new SessionsService(config, new PageService(config))
    val qsid = Qsid("guarded-invalid-dev", UUID.randomUUID().toString)
    val file = savedFile(qsid, files)
    Files.write(file.toPath, corruptBytes)
    await(sessions.initAppState(qsid, request))
    await(sessions.createAppIfNeeded(qsid, request, Queue[Future, String]().stream)) match {
      case SessionStart.Attached(_, release) =>
        try {
          await(sessions.getApp(qsid)).isDefined shouldBe true
          Files.readAllBytes(file.toPath).toSeq shouldBe corruptBytes.toSeq
          new File(file.getPath + ".invalid").exists() shouldBe false
        } finally await(release())
      case SessionStart.Reload() => fail("Guarded sessions must not restore legacy dev buffers")
    }
  }

  it should "preserve both files and the validation error if quarantine loses a race" in withDevMode { files =>
    val qsid = Qsid("invalid-dev-quarantine", UUID.randomUUID().toString)
    val file = savedFile(qsid, files)
    val retained = new File(file.getPath + ".invalid")
    val priorEvidence = Array[Byte](9, 8, 7)
    Files.write(file.toPath, corruptBytes)
    val devMode = new DevMode.ForRenderContext(qsid.toString) {
      override def loadRenderContext() = {
        val buffer = super.loadRenderContext()
        // Another restore can create its marker after our initial marker check.
        // Simulate that ordering without platform-dependent permissions or races.
        Files.write(retained.toPath, priorEvidence)
        buffer
      }
    }
    val error = intercept[DevMode.InvalidSavedRenderContext] {
      devMode.restoreRenderContext[Nothing]()
    }
    error.getCause shouldBe a[avocet.RenderException]
    error.getCause.asInstanceOf[avocet.RenderException].code shouldBe avocet.RenderException.Code.InvalidSavedBuffer
    error.getSuppressed.toSeq should have size 1
    error.getMessage.toLowerCase should include("reload")
    Files.readAllBytes(file.toPath).toSeq shouldBe corruptBytes.toSeq
    Files.readAllBytes(retained.toPath).toSeq shouldBe priorEvidence.toSeq
  }
}
