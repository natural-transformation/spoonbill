package spoonbill.server.internal.services

import LongPollingFixture.*
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.server.{spoonbillService, SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution
import spoonbill.web.{PathAndQuery, Request, Response}

/**
 * A page on long polling says with every request which opening of its
 * connection the request belongs to. These cases drive real sessions through
 * the messaging service and look at what a browser would get: the frames, the
 * status of a poll, and which view answers.
 */
class LongPollingOpeningSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect

  "A poll of the next opening" should "retire the view of the opening before and get a view that announces its events again" in {
    val fixture = new LongPollingFixture
    announcesClick(fixture.frames(Some(1))) shouldBe true
    val first = fixture.view.get
    fixture.frames(Some(1)) shouldBe empty

    val reopened = fixture.frames(Some(2))

    fixture.destroyed shouldBe 1
    fixture.view.get should not be theSameInstanceAs(first)
    announcesClick(reopened) shouldBe true
    fixture.reports.errors shouldBe empty
  }

  it should "attach its view only when the view before has left" in {
    val held    = Promise[Unit]()
    val fixture = new LongPollingFixture(destroy = () => held.future)
    fixture.frames(Some(1))
    val first = fixture.view.get

    val reopening = fixture.subscribe(Some(2))

    // The old view is on its way out and has not left yet. The poll waits for it.
    fixture.destroyed shouldBe 1
    reopening.isCompleted shouldBe false
    fixture.view.foreach(_ should be theSameInstanceAs first)

    held.success(())
    fixture.worker.drain()

    await(reopening).status shouldBe Response.Status.Ok
    fixture.view.get should not be theSameInstanceAs(first)
    fixture.reports.errors shouldBe empty
  }

  "A poll of the same opening" should "stay on its view" in {
    val fixture = new LongPollingFixture
    fixture.frames(Some(1))
    val first = fixture.view.get

    fixture.frames(Some(1)) shouldBe empty

    fixture.view.get should be theSameInstanceAs first
    fixture.destroyed shouldBe 0
  }

  "A poll of an older opening" should "be answered with Gone and leave the view alone" in {
    val fixture = new LongPollingFixture
    fixture.frames(Some(1))
    fixture.frames(Some(2))
    val current = fixture.view.get

    await(fixture.subscribe(Some(1))).status shouldBe Response.Status.Gone

    fixture.view.get should be theSameInstanceAs current
    fixture.destroyed shouldBe 1
  }

  "The first poll of a page" should "keep the state that the page was rendered with" in {
    val fixture = new LongPollingFixture
    await(fixture.sessions.initAppState(fixture.qsid, fixture.request(None)))
    fixture.loads shouldBe 1

    announcesClick(fixture.frames(Some(1))) shouldBe true

    fixture.loads shouldBe 1
    fixture.destroyed shouldBe 0
  }

  "A poll without a number" should "behave as before: one view for the session" in {
    val fixture = new LongPollingFixture
    announcesClick(fixture.frames(None)) shouldBe true
    val first = fixture.view.get

    fixture.frames(None) shouldBe empty

    fixture.view.get should be theSameInstanceAs first
    fixture.destroyed shouldBe 0
  }

  "A message of the next opening" should "start that opening when it comes before the poll, and reach the new view" in {
    val fixture = new LongPollingFixture
    fixture.frames(Some(1))
    val first = fixture.view.get

    await(fixture.publish(Some(2), click)).status shouldBe Response.Status.Ok

    // The message ended the topic of the old bridge, and with it the old view.
    fixture.destroyed shouldBe 1
    val reopened = fixture.frames(Some(2))
    fixture.view.get should not be theSameInstanceAs(first)
    announcesClick(reopened) shouldBe true
    patches(reopened) shouldBe true
    fixture.reports.errors shouldBe empty
  }

  "A message of the same opening" should "reach the view" in {
    val fixture = new LongPollingFixture
    fixture.frames(Some(1))

    await(fixture.publish(Some(1), click)).status shouldBe Response.Status.Ok

    patches(fixture.frames(Some(1))) shouldBe true
    fixture.destroyed shouldBe 0
  }

  "A message of an older opening" should "be answered with Gone and not reach the view" in {
    val fixture = new LongPollingFixture
    fixture.frames(Some(1))
    fixture.frames(Some(2))

    await(fixture.publish(Some(1), click)).status shouldBe Response.Status.Gone

    fixture.frames(Some(2)) shouldBe empty
    fixture.destroyed shouldBe 1
  }

  "The service" should "read the opening on both long-polling routes" in {
    val service = spoonbillService(
      SpoonbillServiceConfig[Future, Int, Any](stateLoader = StateLoader.default[Future, Int](0))(testExecution.defaultExecutor)
    )
    def call(method: Request.Method, path: String): Response.Status = {
      // A heartbeat, the smallest message of a bridge. An empty body is no
      // message, and the view would report it as an invalid frame.
      val body    = await(Stream(Bytes.wrap("[6]".getBytes("UTF-8"))).mat[Future]())
      val request = Request(method, PathAndQuery.fromString(path), Nil, None, body, "deviceId=device")
      await(service.http(request)).status
    }
    val session = "/bridge/long-polling/" + UUID.randomUUID().toString

    call(Request.Method.Get, session + "/subscribe?open=2") shouldBe Response.Status.Ok
    // Without the number these two requests would be served: the poll would
    // wait for a frame, and the message would be taken.
    call(Request.Method.Get, session + "/subscribe?open=1") shouldBe Response.Status.Gone
    call(Request.Method.Post, session + "/publish?open=1") shouldBe Response.Status.Gone
    call(Request.Method.Post, session + "/publish?open=2") shouldBe Response.Status.Ok
  }
}
