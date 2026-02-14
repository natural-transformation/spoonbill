package spoonbill.server.internal.services

import spoonbill.Qsid
import spoonbill.effect.{Effect, Stream}
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization._
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class SessionsServiceSpec extends AnyFlatSpec with Matchers {

  "createAppIfNeeded" should "rebuild missing state and create app" in {
    implicit val effect: Effect[Future] = Effect.futureEffect

    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("initial-state")
    )
    val pageService    = new PageService[Future, String, Any](config)
    val sessionsService = new SessionsService[Future, String, Any](config, pageService)

    val qsid    = Qsid("device", "session")
    val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incomingStream = Stream.endless[Future, String]

    val resultF =
      sessionsService
        .createAppIfNeeded(qsid, request, incomingStream)
        .flatMap(_ => sessionsService.getApp(qsid))

    val result = Await.result(resultF, 3.seconds)
    result.isDefined shouldBe true
  }
}
