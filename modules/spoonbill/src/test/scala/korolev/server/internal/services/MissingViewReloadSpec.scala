package spoonbill.server.internal.services

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{
  SessionAccessControl,
  SessionAccessDenied,
  SessionGuard,
  SpoonbillServiceConfig,
  StateLoader,
  WebSocketResponse
}
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

final class MissingViewReloadSpec extends AnyFlatSpec with Matchers {

  "A guarded missing view" should "return one reload message and release its unused input without opening a view" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val opened = new AtomicInteger(0)
    val canceled = Promise[Unit]()
    val pendingInput = Promise[Option[Bytes]]()
    val incoming = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = pendingInput.future
      def cancel(): Future[Unit] = effect.delay {
        pendingInput.trySuccess(None)
        canceled.trySuccess(())
        ()
      }
    }
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
      def open(qsid: Qsid, request: Request.Head, connectionId: ConnectionId): Future[SessionGuard[Future, String]] = {
        opened.incrementAndGet()
        Future.failed(new SessionAccessDenied)
      }
      // The default resume=None requests reload for an unavailable ephemeral view.
    }
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("initial"),
      sessionAccessControl = Some(control)
    )
    val sessions = new SessionsService[Future, String, Any](config, new PageService[Future, String, Any](config))
    val messaging = new MessagingService[Future](
      reporter = config.reporter,
      commonService = new CommonService[Future],
      sessionsService = sessions,
      compressionSupport = None,
      orphanTopicTimeout = 1.second
    )
    val qsid = Qsid("device", "missing-view")
    val request = Request(Request.Method.Get, PathAndQuery.fromString("/bridge/web-socket/missing-view"), Nil, None, ())

    val result = for {
      response <- messaging.webSocketMessaging(qsid, request, incoming, Seq("json"))
      _ = info(s"Input canceled before the core response was returned: ${canceled.isCompleted}")
      terminal <- response match {
                    case WebSocketResponse.SendThenClose(output, protocol, _) =>
                      for {
                        first <- output.pull()
                        end   <- output.pull()
                      } yield (protocol, first, end)
                    case WebSocketResponse.Duplex(_, _, _) =>
                      fail("A declined missing view is terminal output, not a duplex session")
                  }
      // Cleanup may finish before or after the response; only eventual release is required.
      _ <- canceled.future
      app <- sessions.getApp(qsid)
    } yield {
      val (protocol, first, end) = terminal
      protocol shouldBe "json"
      first.map(_.asUtf8String) shouldBe Some("[1]")
      end shouldBe None
      app shouldBe None
      opened.get() shouldBe 0
    }

    Await.result(result, 3.seconds)
  }
}
