package spoonbill.server.internal.services

import LongPollingFixture.*
import avocet.Id
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.concurrent.TrieMap
import scala.concurrent.{Future, Promise}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Queue, Reporter, Stream}
import spoonbill.internal.ExtensionRuntime
import spoonbill.internal.TimerTestSupport.Reports
import spoonbill.server.{spoonbillService, SpoonbillServiceConfig, StateLoader}
import spoonbill.state.{StateManager, StateStorage}
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

  it should "leave the stored state of the session in place" in {
    // What a database would hold, and how often the storage asked it to delete a session.
    val repository = TrieMap.empty[String, Map[Id, Array[Byte]]]
    var removals   = 0
    val storage = StateStorage.fromRepository[Future, Int](
      repositoryExists = key => Future.successful(repository.contains(key)),
      loadSnapshot = key => Future.successful(repository.getOrElse(key, Map.empty)),
      createSnapshot = (key, snapshot) => Future.successful { repository.put(key, snapshot); () },
      writeNode = (key, id, bytes) => Future.successful { repository.updateWith(key)(_.map(_.updated(id, bytes))); () },
      deleteNode = (key, id) => Future.successful { repository.updateWith(key)(_.map(_ - id)); () },
      removeRepository = key => Future.successful { removals += 1; repository.remove(key); () },
      onRemoveError = _ => ()
    )
    val fixture = new LongPollingFixture(storage = Some(storage))
    fixture.frames(Some(1))
    val first = fixture.view.get

    announcesClick(fixture.frames(Some(2))) shouldBe true

    // The page goes on, so its state is not removed. A removal that only
    // starts here could still run when the new view has attached.
    fixture.view.get should not be theSameInstanceAs(first)
    removals shouldBe 0
    repository.size shouldBe 1
    fixture.loads shouldBe 1
  }

  it should "go on from the state that the session had" in {
    val fixture = new LongPollingFixture
    fixture.frames(Some(1))
    await(fixture.publish(Some(1), click)).status shouldBe Response.Status.Ok
    patches(fixture.frames(Some(1))) shouldBe true

    fixture.frames(Some(2))
    await(fixture.publish(Some(2), click)).status shouldBe Response.Status.Ok

    // The count was 1. The new view starts there, so its click gives 2.
    val rendered = fixture.frames(Some(2)).filter(_.startsWith("[4,"))
    rendered should have size 1
    rendered.head should include("\"2\"")
    fixture.loads shouldBe 1
  }

  "A poll that waits for a view which cannot be retired" should "fail, and not wait for ever" in {
    val manager = StateManager.cached[Future](Map(Id.TopLevel -> 0))
    // A storage that cannot remove a state, and a reporter that cannot report
    // it: the retirement of a view ends with an error half way, and the view
    // stays registered.
    val storage = new StateStorage[Future, Int] {
      def exists(device: String, session: String): Future[Boolean]                           = Future.successful(true)
      def create(device: String, session: String, value: Int): Future[StateManager[Future]] = Future.successful(manager)
      def get(device: String, session: String): Future[StateManager[Future]]                 = Future.successful(manager)
      def remove(device: String, session: String): Unit                                      = throw new IllegalStateException("not removed")
    }
    def unreported(reports: Reports): Reporter = new Reporter {
      def error(message: String, cause: Throwable): Unit =
        if (message == "Legacy session cleanup failed") throw cause else reports.error(message, cause)
      def error(message: String): Unit                                  = reports.error(message)
      def warning(message: String, cause: Throwable): Unit              = reports.warning(message, cause)
      def warning(message: String): Unit                                = reports.warning(message)
      def info(message: String): Unit                                   = ()
      def debug(message: String): Unit                                  = ()
      def debug(message: String, arg1: Any): Unit                       = ()
      def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
      def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
    }
    val fixture = new LongPollingFixture(storage = Some(storage), reporter = unreported)
    // A view that a socket attached. It names no owner, so a poll of the same
    // session has to wait until it has left. Its state is removed when its
    // socket closes; the state of a view that the next opening replaces is not.
    val socket = Queue[Future, String]()
    fixture.sessions.createAppIfNeeded(fixture.qsid, fixture.request(None), socket.stream)
    fixture.worker.drain()
    val poll = fixture.subscribe(Some(1))
    poll.isCompleted shouldBe false

    await(socket.close())
    fixture.worker.drain()

    poll.value.map(_.failed.map(_.getMessage).toOption) shouldBe Some(Some("not removed"))
  }

  "A poll of an opening that is replaced while it waits" should "be answered with Gone at once, and get no view" in {
    val held    = Promise[Unit]()
    val fixture = new LongPollingFixture(destroy = () => held.future)
    fixture.frames(Some(1))
    val second = fixture.subscribe(Some(2))
    fixture.status(second) shouldBe None // it waits for the view of opening 1 to leave

    val third = fixture.subscribe(Some(3))

    // Opening 3 took the place of opening 2. The poll of opening 2 is answered
    // now, while the view of opening 1 is still on its way out.
    fixture.status(second) shouldBe Some(Response.Status.Gone)
    fixture.status(third) shouldBe None

    held.success(())
    fixture.worker.drain()

    fixture.status(third) shouldBe Some(Response.Status.Ok)
    // One view was destroyed: the one of opening 1. None was built for opening 2.
    fixture.destroyed shouldBe 1
    fixture.reports.errors shouldBe empty
  }

  it should "build no view when the replacement comes just as the old view has left" in {
    val held    = Promise[Unit]()
    val fixture = new LongPollingFixture(destroy = () => held.future)
    fixture.frames(Some(1))
    val second = fixture.subscribe(Some(2))
    // The view of opening 1 leaves. The poll of opening 2 is woken, but its
    // next step waits in the worker.
    held.success(())
    // Before that step runs, a message of opening 3 takes the place of opening 2.
    val body = await(Stream(Bytes.wrap(click.getBytes("UTF-8"))).mat[Future]())
    fixture.messaging.longPollingPublish(fixture.qsid, body, Some(3))

    fixture.worker.drain()

    // No view is registered now, and the poll of opening 2 must not build one.
    fixture.status(second) shouldBe Some(Response.Status.Gone)
    fixture.view shouldBe None
    fixture.destroyed shouldBe 1
    // The view of opening 3 is the next one, and it gets the message.
    patches(fixture.frames(Some(3))) shouldBe true
    fixture.destroyed shouldBe 1
  }

  "A poll of the next opening that comes while the view before is being set up" should
    "fail with that view, and leave a view to the opening after it" in {
    val gate    = Promise[Unit]()
    val fixture = new LongPollingFixture(setup = Some(() => gate.future))
    val first   = fixture.subscribe(Some(1))
    val second  = fixture.subscribe(Some(2))
    // Opening 2 ended the topic of opening 1. The view of opening 1 is still
    // in its setup, and both polls wait for it.
    first.isCompleted shouldBe false
    second.isCompleted shouldBe false

    gate.success(())
    fixture.worker.drain()

    // The setup stops, because its connection is over. Both polls get that.
    first.value.flatMap(_.failed.toOption).get shouldBe a[ExtensionRuntime.SetupAborted]
    second.value.flatMap(_.failed.toOption).get shouldBe a[ExtensionRuntime.SetupAborted]
    fixture.view shouldBe None
    // The failure is not kept for the session: the next opening gets a view.
    announcesClick(fixture.frames(Some(3))) shouldBe true
    fixture.view should not be empty
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
