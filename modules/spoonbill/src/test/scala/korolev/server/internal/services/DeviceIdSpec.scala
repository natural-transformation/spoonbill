package spoonbill.server.internal.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.server.{spoonbillService, SpoonbillServiceConfig, StateLoader, WebSocketRequest}
import spoonbill.server.internal.BadRequestException
import spoonbill.state.IdGenerator
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

final class DeviceIdSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future]   = Effect.futureEffect

  private def init(rawCookie: String, generated: String = "replacement-device"): String = {
    val generator = new IdGenerator[Future] {
      def generateDeviceId()(implicit F: Effect[Future]): Future[String]  = Future.successful(generated)
      def generateSessionId()(implicit F: Effect[Future]): Future[String] = Future.successful("session")
    }
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("state"),
      idGenerator = generator
    )
    val service = new SessionsService(config, new PageService(config))
    val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, (), rawCookie)
    Await.result(service.initSession(request), 3.seconds).deviceId
  }

  "device IDs" should "accept existing UUIDs, base64url IDs, and bounded custom generators" in {
    Seq("e83efb64-cb57-4185-8e55-1fa86d80c7de", "aB_09-.~", "x" * 128).foreach { id =>
      init("deviceId=" + id) shouldBe id
      init(null, id) shouldBe id
    }
  }

  it should "replace malformed, ambiguous, oversized, and unsafe input" in {
    Seq(
      "",
      "%",
      "%GG",
      "%0d%0AX-Canary%3a+yes",
      "x%3By",
      "x+y",
      "x%2Fy",
      "x" * 129,
      "first;deviceId=second",
      "first;%64eviceId=second"
    ).foreach { value =>
      init("deviceId=" + value) shouldBe "replacement-device"
    }
  }

  it should "fail invalid custom generator output before initializing state" in {
    Seq(null, "", "x\r\nX-Canary: yes", "x/y", "ü", "x" * 129).foreach { id =>
      intercept[IllegalArgumentException](init(null, id))
    }
  }

  it should "treat invalid device IDs as absent on device-bound transport routes" in {
    val service = spoonbillService(
      SpoonbillServiceConfig[Future, String, Any](
        stateLoader = StateLoader.default[Future, String]("state")
      )
    )
    Seq("x%0d%0a", "x%3By", "x" * 129).foreach { value =>
      val request = Request(
        Request.Method.Get,
        PathAndQuery.fromString("/bridge/long-polling/session/subscribe"),
        Nil,
        None,
        Stream.empty[Future, Bytes],
        "deviceId=" + value
      )
      Await.result(service.http(request), 3.seconds).status.code shouldBe 404
      val websocket =
        WebSocketRequest(request.copy(pq = PathAndQuery.fromString("/bridge/web-socket/session")), Seq("json"))
      intercept[BadRequestException](Await.result(service.ws(websocket), 3.seconds))
    }
  }
}
