package spoonbill.server.internal.services

import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.annotation.tailrec
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}
import spoonbill.{Context, Extension, Qsid}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.internal.TimerTestSupport.{Reports, Worker}
import spoonbill.server.{spoonbillService, HttpResponse, SpoonbillServiceConfig, StateLoader}
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
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  /**
   * One session of a counter page. The worker runs the tasks of the service when a case drains it.
   *
   * @param destroy
   *   what the page's extension does when its view is destroyed
   * @param storage
   *   the state storage, when a case needs one that misbehaves
   * @param reporter
   *   the reporter of the service, when a case needs one that misbehaves
   */
  private final class Fixture(
    destroy: () => Future[Unit] = () => Future.unit,
    storage: Option[StateStorage[Future, Int]] = None,
    reporter: Reports => Reporter = identity
  ) {
    val worker    = new Worker
    val reports   = new Reports
    var loads     = 0 // how often the state was built from a request
    var destroyed = 0 // how many views were destroyed
    private val context = Context[Future, Int, Any]
    import context.*
    private val config = SpoonbillServiceConfig[Future, Int, Any](
      stateLoader = (_, _) => { loads += 1; Future.successful(0) },
      document = state =>
        Html(
          head(title("Counter")),
          body(button("Add", event("click")(access => access.transition(_ + 1))), p(state.toString))
        ),
      reporter = reporter(reports),
      stateStorage = storage.orNull,
      extensions = List(
        Extension.pure[Future, Int, Any]("retirement")(_ =>
          Extension.Handlers[Future, Int, Any](onDestroy = _ => { destroyed += 1; destroy() })
        )
      )
    )(worker)
    val sessions = new SessionsService(config, new PageService(config))
    val messaging = new MessagingService[Future](
      reporter = reports,
      // Not used by the long-polling requests.
      commonService = null.asInstanceOf[CommonService[Future]],
      sessionsService = sessions,
      compressionSupport = None,
      orphanTopicTimeout = 1.minute
    )
    val qsid = Qsid("long-polling", UUID.randomUUID().toString)

    def request(open: Option[Long]): Request[Unit] = {
      val path = "/bridge/long-polling/session/subscribe" + open.fold("")(number => s"?open=$number")
      Request(Request.Method.Get, PathAndQuery.fromString(path), Nil, None, ())
    }

    /** One poll. It is answered when the view has a frame, or when the view has ended. */
    def subscribe(open: Option[Long]): Future[HttpResponse[Future]] = {
      val poll = messaging.longPollingSubscribe(qsid, request(open))
      worker.drain()
      poll
    }

    /** One message of a bridge, as its publish request carries it. */
    def publish(open: Option[Long], message: String): Future[HttpResponse[Future]] = {
      val body = await(Stream(Bytes.wrap(message.getBytes("UTF-8"))).mat[Future]())
      val sent = messaging.longPollingPublish(qsid, body, open)
      worker.drain()
      sent
    }

    def view: Option[AnyRef] = await(sessions.getApp(qsid))

    private var outstanding = Map.empty[Option[Long], Future[HttpResponse[Future]]]

    /** The frames that are ready for this opening, in order. One poll stays outstanding, as in a browser. */
    def frames(open: Option[Long]): Vector[String] = {
      @tailrec def collect(done: Vector[String]): Vector[String] = {
        val poll = outstanding.getOrElse(open, subscribe(open))
        worker.drain()
        poll.value match {
          case Some(Success(response)) if response.status == Response.Status.Ok =>
            outstanding -= open
            collect(done :+ await(response.body.fold(Bytes.empty)(_ ++ _)).asUtf8String)
          case Some(Success(_)) => outstanding -= open; done
          case Some(Failure(error)) => throw error
          case None => outstanding += open -> poll; done
        }
      }
      collect(Vector.empty)
    }
  }

  /** The frame that tells a bridge to listen to clicks. A view sends it once. */
  private def announcesClick(frames: Vector[String]): Boolean = frames.exists(_.startsWith("""[2,"click""""))

  /** A view that handled a click renders the next state and sends the patch. */
  private def patches(frames: Vector[String]): Boolean = frames.exists(_.startsWith("[4,"))

  /** The first click on the button, as a bridge sends it. The button is the first child of the body. */
  private val click = """[0,"0:1_2_1:click"]"""

  "A poll of the next opening" should "retire the view of the opening before and get a view that announces its events again" in {
    val fixture = new Fixture
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
    val fixture = new Fixture(destroy = () => held.future)
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

  it should "fail, and not wait for ever, when the view before cannot be retired" in {
    val manager = StateManager.cached[Future](Map(Id.TopLevel -> 0))
    // A storage that cannot remove a state, and a reporter that cannot report
    // it: the retirement of the old view ends with an error half way, and the
    // view stays registered.
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
    val fixture = new Fixture(storage = Some(storage), reporter = unreported)
    fixture.frames(Some(1))

    val reopening = fixture.subscribe(Some(2))

    reopening.value.map(_.failed.map(_.getMessage).toOption) shouldBe Some(Some("not removed"))
  }

  "A poll of the same opening" should "stay on its view" in {
    val fixture = new Fixture
    fixture.frames(Some(1))
    val first = fixture.view.get

    fixture.frames(Some(1)) shouldBe empty

    fixture.view.get should be theSameInstanceAs first
    fixture.destroyed shouldBe 0
  }

  "A poll of an older opening" should "be answered with Gone and leave the view alone" in {
    val fixture = new Fixture
    fixture.frames(Some(1))
    fixture.frames(Some(2))
    val current = fixture.view.get

    await(fixture.subscribe(Some(1))).status shouldBe Response.Status.Gone

    fixture.view.get should be theSameInstanceAs current
    fixture.destroyed shouldBe 1
  }

  "The first poll of a page" should "keep the state that the page was rendered with" in {
    val fixture = new Fixture
    await(fixture.sessions.initAppState(fixture.qsid, fixture.request(None)))
    fixture.loads shouldBe 1

    announcesClick(fixture.frames(Some(1))) shouldBe true

    fixture.loads shouldBe 1
    fixture.destroyed shouldBe 0
  }

  "A poll without a number" should "behave as before: one view for the session" in {
    val fixture = new Fixture
    announcesClick(fixture.frames(None)) shouldBe true
    val first = fixture.view.get

    fixture.frames(None) shouldBe empty

    fixture.view.get should be theSameInstanceAs first
    fixture.destroyed shouldBe 0
  }

  "A message of the next opening" should "start that opening when it comes before the poll, and reach the new view" in {
    val fixture = new Fixture
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
    val fixture = new Fixture
    fixture.frames(Some(1))

    await(fixture.publish(Some(1), click)).status shouldBe Response.Status.Ok

    patches(fixture.frames(Some(1))) shouldBe true
    fixture.destroyed shouldBe 0
  }

  "A message of an older opening" should "be answered with Gone and not reach the view" in {
    val fixture = new Fixture
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
      val request = Request(method, PathAndQuery.fromString(path), Nil, None, Stream.empty[Future, Bytes], "deviceId=device")
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
