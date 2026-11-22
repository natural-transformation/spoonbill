package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.CancellationException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.{Extension, Qsid}
import spoonbill.effect.{Effect, Queue}
import spoonbill.internal.TimerTestSupport.{Reports, Worker}
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

class LegacyStreamFailureSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  for (canceled <- List(false, true)) {
    "A failed legacy incoming stream" should s"retire and reconnect with appropriate diagnostics (cancellation: $canceled)" in {
      val worker    = new Worker
      val reports   = new Reports
      var destroyed = 0
      val config = SpoonbillServiceConfig[Future, Int, Any](
        stateLoader = StateLoader.default(0),
        document = state => Html(body(state.toString)),
        reporter = reports,
        extensions = List(
          Extension.pure[Future, Int, Any]("retirement")(_ =>
            Extension.Handlers[Future, Int, Any](onDestroy = _ => { destroyed += 1; Future.unit })
          )
        )
      )(worker)
      val service  = new SessionsService(config, new PageService(config))
      val qsid     = Qsid("legacy-stream-failure", UUID.randomUUID().toString)
      val request  = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
      val incoming = Queue[Future, String]()
      // A page is rendered before it connects. An attach never builds a state.
      val rendered = service.initAppState(qsid, request)
      worker.drain()
      await(rendered)
      val first = await(service.createAppIfNeeded(qsid, request, incoming.stream))
        .asInstanceOf[SessionStart.Attached[Future]]
      worker.drain()
      val previous = await(service.getApp(qsid)).get
      try {
        val terminal =
          if (canceled) new CancellationException("incoming transport canceled")
          else new IllegalStateException("incoming transport failed")
        await(incoming.fail(terminal))
        worker.drain()
        destroyed shouldBe 1
        await(service.getApp(qsid)) shouldBe None
        reports.errors.exists { case (message, error) =>
          message == "Legacy connection closed with an error" && (error eq terminal)
        } shouldBe !canceled
        val second = await(service.createAppIfNeeded(qsid, request, Queue[Future, String]().stream))
          .asInstanceOf[SessionStart.Attached[Future]]
        worker.drain()
        try {
          await(service.getApp(qsid)).get should not be theSameInstanceAs(previous)
          await(second.outgoing.pull()).isDefined shouldBe true
        } finally { await(second.release()); worker.drain() }
      } finally { await(first.release()); worker.drain() }
    }
  }
}
