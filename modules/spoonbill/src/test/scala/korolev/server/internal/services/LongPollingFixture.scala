package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.{Context, Qsid}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler, Stream}
import spoonbill.internal.TimerTestSupport.{Reports, Worker}
import spoonbill.server.{HttpResponse, SpoonbillServiceConfig, StateLoader}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * One server with a counter page on long polling, for [[LongPollingDeliverySpec]].
 * The worker runs the tasks of the services when a case drains it, and the
 * manual clock moves only when a case moves it: the idle timeout of a view,
 * the wait limit of a poll and the age of a fresh-page record all read it.
 *
 * @param idleTimeout
 *   the idle timeout of a session. A poll waits half of it.
 * @param heartbeats
 *   whether the view answers a heartbeat
 * @param storage
 *   the state storage, when a case needs another one
 */
private[services] final class LongPollingFixture(
  idleTimeout: FiniteDuration = 10.seconds,
  heartbeats: Boolean = true,
  storage: Option[StateStorage[Future, Int]] = None
) {
  import LongPollingFixture.*
  private given Effect[Future] = Effect.futureEffect

  val worker  = new Worker
  val reports = new Reports
  val time    = new ManualTime(worker)

  private val context = Context[Future, Int, Any]
  import context.*
  private val config = SpoonbillServiceConfig[Future, Int, Any](
    stateLoader = StateLoader.default(0),
    document = state => Html(body(button("Add", event("click")(access => access.transition(_ + 1))), p(s"count $state"))),
    reporter = reports,
    stateStorage = storage.orNull,
    sessionIdleTimeout = idleTimeout,
    heartbeatLimit = Option.when(heartbeats)(2)
  )(worker)
  val sessions = new SessionsService(
    config,
    new PageService(config),
    idleTiming = Some(time.idle),
    nanoTime = () => time.nanos
  )
  val messaging = new MessagingService[Future](
    reporter = reports,
    // Not used by the long-polling requests.
    commonService = null.asInstanceOf[CommonService[Future]],
    sessionsService = sessions,
    compressionSupport = None,
    pollWait = idleTimeout / 2,
    pollTiming = Some(time.poll)
  )

  /** One page of the server: a session and the page id of its document. */
  final case class Page(qsid: Qsid, id: UUID = UUID.randomUUID())

  /** Renders a new page, as a browser loads it before it connects. */
  def render(): Page = {
    val page     = Page(Qsid("long-polling", UUID.randomUUID().toString))
    val rendered = sessions.initAppState(page.qsid, request("/"))
    worker.drain()
    await(rendered)
    page
  }

  private def request(path: String): Request[Unit] =
    Request(Request.Method.Get, PathAndQuery.fromString(path), Nil, None, ())

  private def query(page: Page, seen: Option[Long]): String =
    seen.fold(s"page=${page.id}")(value => s"page=${page.id}&seen=$value")

  /** One poll of `page` with cursor `seen`, or without a cursor. */
  def poll(page: Page, seen: Option[Long]): Future[HttpResponse[Future]] = {
    val answer = messaging.longPollingSubscribe(page.qsid, request(s"/bridge/long-polling/x/subscribe?${query(page, seen)}"))
    worker.drain()
    answer
  }
  def poll(page: Page, seen: Long): Future[HttpResponse[Future]] = poll(page, Some(seen))

  /** A poll without any query, as an old client sends it. */
  def bare(page: Page): Future[HttpResponse[Future]] = {
    val answer = messaging.longPollingSubscribe(page.qsid, request("/bridge/long-polling/x/subscribe"))
    worker.drain()
    answer
  }

  /** One message of `page` with cursor `seen`. */
  def publish(page: Page, seen: Long, message: String): Future[HttpResponse[Future]] = {
    val body = await(Stream(Bytes.wrap(message.getBytes("UTF-8"))).mat[Future]())
    val sent = messaging.longPollingPublish(page.qsid, request(s"/bridge/long-polling/x/publish?${query(page, Some(seen))}"), body)
    worker.drain()
    sent
  }

  /** A click on the button, as a page of DOM revision `revision` sends it. */
  def click(page: Page, seen: Long, revision: Long, counter: Int = 0): Future[HttpResponse[Future]] =
    publish(page, seen, s"""[7,"${page.id}:$revision:0:$counter:${target(page)}:click"]""")

  /** A heartbeat of the page. */
  def heartbeat(page: Page, seen: Long): Future[HttpResponse[Future]] = publish(page, seen, """[6,"0"]""")

  /** The id of the button of the view of `page`. */
  def target(page: Page): String = {
    val app = sessions.getApp(page.qsid)
    worker.drain()
    await(app)
      .getOrElse(throw new AssertionError("The page has no view"))
      .topLevelComponentInstance
      .allEventHandlers
      .keys
      .filter(id => id.`type` == "click" && id.phase == avocet.events.EventPhase.Bubbling)
      .map(_.target.mkString)
      .head
  }

  /** Whether the session of `page` has a view. */
  def hasView(page: Page): Boolean = {
    val app = sessions.getApp(page.qsid)
    worker.drain()
    await(app).nonEmpty
  }

  /** A WebSocket attach of `page`, with the input that it comes through. */
  def webSocket(page: Page, input: Queue[Future, String] = Queue[Future, String]()): Future[SessionStart[Future]] = {
    val start = sessions.createAppIfNeeded(page.qsid, request("/bridge/web-socket/x"), input.stream)
    worker.drain()
    start
  }

  /** Moves the manual clock, and runs what is due. */
  def advance(by: FiniteDuration): Unit = time.advance(by)
}

private[services] object LongPollingFixture {
  def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  /** What a case reads from a poll answer. */
  final case class Answer(status: Int, frame: Option[Long], dom: Option[Long], body: String, cache: Option[String])

  def read(response: HttpResponse[Future])(using Effect[Future]): Answer = {
    def header(name: String) = response.headers.collectFirst { case (key, value) if key.equalsIgnoreCase(name) => value }
    val body = await(response.body.fold(Bytes.empty)(_ ++ _)).asUtf8String
    Answer(response.status.code, header("spoonbill-frame").map(_.toLong), header("spoonbill-dom").map(_.toLong), body, header("cache-control"))
  }

  /**
   * One clock for the idle timeouts of the session service, the wait limits of
   * the polls and the ages of the fresh-page records. A due job runs on the
   * worker when the clock reaches it.
   */
  final class ManualTime(worker: Worker)(using effect: Effect[Future]) {
    private final class Task(val deadline: Long, job: () => Future[Unit]) extends Scheduler.JobHandler[Future, Unit] {
      @volatile var canceled = false
      @volatile var fired    = false
      def unsafeCancel(): Unit           = canceled = true
      def cancel(): Future[Unit]         = effect.delay(unsafeCancel())
      def result: Future[Unit]           = Future.unit
      def fire(): Unit = if (!canceled && !fired) { fired = true; effect.runAsync(effect.delayAsync(job()))(_ => ()) }
    }
    private val clock = new AtomicReference(0L) // milliseconds
    private val tasks = new AtomicReference(Vector.empty[Task])
    private def schedule(delay: FiniteDuration, job: () => Future[Unit]): Task = {
      val task = new Task(clock.get() + delay.toMillis, job)
      tasks.updateAndGet(_ :+ task)
      task
    }
    def millis: Long = clock.get()
    def nanos: Long  = clock.get() * 1000000L

    val idle: SessionsService.IdleTiming[Future] = new SessionsService.IdleTiming[Future] {
      def unsafeScheduleOnce(delay: FiniteDuration)(job: => Future[Unit]): Scheduler.JobHandler[Future, Unit] =
        schedule(delay, () => job)
      def scheduleOnce(delay: FiniteDuration)(job: => Future[Unit]): Future[Scheduler.JobHandler[Future, Unit]] =
        effect.delay(schedule(delay, () => job))
    }
    val poll: LongPollingDelivery.Timing[Future] = new LongPollingDelivery.Timing[Future] {
      def scheduleOnce(delay: FiniteDuration)(job: => Future[Unit]): Future[Scheduler.JobHandler[Future, Unit]] =
        effect.delay(schedule(delay, () => job))
    }

    /** Moves the clock in steps of 100 ms, and runs every job that falls due, in order. */
    def advance(by: FiniteDuration): Unit = {
      val end = clock.get() + by.toMillis
      while (clock.get() < end) {
        clock.set(math.min(end, clock.get() + 100L))
        worker.drain()
        tasks.get().filter(task => task.deadline <= clock.get()).sortBy(_.deadline).foreach(_.fire())
        worker.drain()
      }
    }
  }
}
