package spoonbill.server.internal.services

import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Success
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler, Stream}
import spoonbill.internal.Frontend
import spoonbill.internal.TimerTestSupport.Worker
import spoonbill.server.HttpResponse
import spoonbill.state.javaSerialization.*

/**
 * Long polling that resumes, through the two routes of the messaging service.
 * A page names itself and the last frame that it applied; the server answers
 * with exactly the next frame, and everything that does not fit ends in a
 * reload.
 */
class LongPollingDeliverySpec extends AnyFlatSpec with Matchers {
  import LongPollingFixture.*
  private given Effect[Future] = Effect.futureEffect

  private val reload = "[1]"

  /** The answers of a page that applies every frame, until a poll waits. */
  private def frames(f: LongPollingFixture, page: f.Page, from: Long): (Vector[Answer], Future[HttpResponse[Future]]) = {
    var seen    = from
    var answers = Vector.empty[Answer]
    var next    = f.poll(page, seen)
    while (next.isCompleted) {
      val answer = read(await(next))
      answers :+= answer
      answer.frame.getOrElse(fail(s"Not a frame: $answer")) shouldBe seen + 1
      seen = answer.frame.get
      next = f.poll(page, seen)
    }
    (answers, next)
  }

  /** The DOM revision that a page counts: the DOM frames among the answers. */
  private def domFrames(answers: Vector[Answer]): Long = answers.count(_.body.startsWith("[4")).toLong

  /**
   * A page that was just rendered makes its first poll and applies the frames
   * that a new view sends at once: it resets the event counters and listens
   * to its events. Then its poll waits.
   *
   * @return
   *   the page, its cursor, its DOM revision and the poll that waits
   */
  private def start(f: LongPollingFixture): (f.Page, Long, Long, Future[HttpResponse[Future]]) = {
    val page                = f.render()
    val (initial, waiting)  = frames(f, page, 0L)
    initial should not be empty
    (page, initial.size.toLong, domFrames(initial), waiting)
  }

  "The DOM revision of an answer" should "belong to its frame, not to what the view has published by now" in {
    val f                        = new LongPollingFixture()
    val (page, seen, dom, first) = start(f)
    // A heartbeat reply is made at the page's DOM revision.
    await(f.heartbeat(page, seen))
    val heartbeat = read(await(first))
    heartbeat shouldBe Answer(200, Some(seen + 1L), Some(dom), "[16]", Some("no-cache"))
    // That answer is lost. A click of that display makes the view publish a patch.
    await(f.click(page, seen = seen, revision = dom)).status.code shouldBe 200
    // The page asks for the heartbeat frame again. It keeps its own revision.
    read(await(f.poll(page, seen))) shouldBe heartbeat
    // Then it confirms it, and every later frame names what the page shows after it.
    val (after, _) = frames(f, page, seen + 1L)
    after.exists(_.body.startsWith("[4")) shouldBe true
    after.zipWithIndex.foreach { case (answer, index) =>
      answer.dom shouldBe Some(dom + domFrames(after.take(index + 1)))
    }
  }

  "A first poll" should "build a view for a page that was just rendered, and get its first frames" in {
    val f                      = new LongPollingFixture()
    val (page, seen, _, poll)  = start(f)
    f.hasView(page) shouldBe true
    poll.isCompleted shouldBe false
    await(f.heartbeat(page, seen))
    read(await(poll)).frame shouldBe Some(seen + 1L)
  }

  it should "be told to reload when the server did not render the page, as after a restart" in {
    val f        = new LongPollingFixture()
    val stranger = f.Page(spoonbill.Qsid("long-polling", UUID.randomUUID().toString))
    read(await(f.poll(stranger, 0L))) shouldBe Answer(200, None, None, reload, Some("no-cache"))
    f.hasView(stranger) shouldBe false
  }

  it should "be told to reload when a WebSocket has taken the record" in {
    val f    = new LongPollingFixture()
    val page = f.render()
    f.webSocket(page).value match {
      case Some(Success(SessionStart.Attached(_, _))) => ()
      case other                                      => fail(s"The WebSocket got no view: $other")
    }
    read(await(f.poll(page, 0L))).body shouldBe reload
    // The view of the WebSocket goes on.
    f.hasView(page) shouldBe true
  }

  it should "be told to reload when its page is older than its lifetime, also when nothing else was rendered" in {
    val f    = new LongPollingFixture(idleTimeout = 10.seconds)
    val page = f.render()
    f.advance(11.seconds)
    read(await(f.poll(page, 0L))).body shouldBe reload
    f.hasView(page) shouldBe false
  }

  it should "build one view for two polls of one page, and give both the same frame" in {
    val f                        = new LongPollingFixture()
    val (page, seen, _, first)   = start(f)
    val second                   = f.poll(page, seen)
    await(f.heartbeat(page, seen))
    read(await(first)) shouldBe read(await(second))
    read(await(first)).frame shouldBe Some(seen + 1L)
  }

  it should "be told to reload when the view that a poll built has ended, also while the storage keeps the state" in {
    // In memory whatever the development-mode switch of the JVM says.
    val storage = new spoonbill.state.StateStorage.DefaultStateStorage[Future, Int](5000, allowDevMode = false)
    val f       = new LongPollingFixture(idleTimeout = 10.seconds, storage = Some(storage))
    val page    = f.render()
    read(await(f.poll(page, 0L))).frame shouldBe Some(1L)
    // The page makes no progress any more: its view ends by the idle timeout.
    f.advance(11.seconds)
    f.hasView(page) shouldBe false
    await(storage.exists(page.qsid.deviceId, page.qsid.sessionId)) shouldBe true
    // The same page polls again with seen=0. Its record was claimed by the first
    // poll, so no second view is built from the state that is still there.
    read(await(f.poll(page, 0L))).body shouldBe "[1]"
    f.hasView(page) shouldBe false
  }

  "A lost answer" should "be answered again with the same frame while the view lives" in {
    val f                      = new LongPollingFixture()
    val (page, seen, _, poll)  = start(f)
    await(f.heartbeat(page, seen))
    val answer = read(await(poll))
    read(await(f.poll(page, seen))) shouldBe answer
    read(await(f.poll(page, seen))) shouldBe answer
  }

  "A poll that waits" should "be answered with 204 at half the idle timeout, and the page goes on" in {
    val f                      = new LongPollingFixture(idleTimeout = 10.seconds)
    val (page, seen, _, poll)  = start(f)
    f.advance(4900.millis)
    poll.isCompleted shouldBe false
    f.advance(200.millis)
    read(await(poll)) shouldBe Answer(204, None, None, "", Some("no-cache"))
    val again = f.poll(page, seen)
    await(f.heartbeat(page, seen))
    read(await(again)).frame shouldBe Some(seen + 1L)
  }

  it should "be told to reload when the view ends meanwhile" in {
    val f                         = new LongPollingFixture()
    val (page, _, _, waiting)     = start(f)
    // A poll that waits is progress, so the idle timer cannot end the view
    // under it. The view ends in another way: its frontend closes.
    val app = f.sessions.getApp(page.qsid)
    f.worker.drain()
    await(await(app).get.frontend.close())
    f.worker.drain()
    read(await(waiting)) shouldBe Answer(200, None, None, reload, Some("no-cache"))
  }

  "Only progress" should "keep a view alive: a page that never moves its cursor loses it after the idle timeout" in {
    val f                        = new LongPollingFixture(idleTimeout = 10.seconds)
    val (page, seen, _, first)   = start(f)
    await(f.heartbeat(page, seen))
    read(await(first)).frame shouldBe Some(seen + 1L)
    // The page never confirms that frame. It asks for it again and again, and
    // sends heartbeats.
    for (_ <- 1 to 12) {
      f.advance(1.second)
      if (f.hasView(page)) {
        read(await(f.poll(page, seen))).frame shouldBe Some(seen + 1L)
        await(f.heartbeat(page, seen))
      }
    }
    f.hasView(page) shouldBe false
    read(await(f.poll(page, seen))).body shouldBe reload
  }

  it should "keep the view of a quiet page whose poll waits, without heartbeats, over two idle periods" in {
    val f                        = new LongPollingFixture(idleTimeout = 10.seconds, heartbeats = false)
    val (page, seen, _, waiting) = start(f)
    var poll                     = waiting
    for (_ <- 1 to 5) {
      f.advance(5.seconds)
      read(await(poll)).status shouldBe 204
      poll = f.poll(page, seen)
    }
    f.hasView(page) shouldBe true
  }

  "A poll" should "be told to reload when its cursor is ahead, it names another page, or it has no cursor" in {
    val f                      = new LongPollingFixture()
    val (page, seen, _, poll)  = start(f)
    await(f.heartbeat(page, seen))
    read(await(poll)).frame shouldBe Some(seen + 1L)
    read(await(f.poll(page, seen + 2L))).body shouldBe reload
    read(await(f.poll(page.copy(id = UUID.randomUUID()), seen))).body shouldBe reload
    read(await(f.poll(page, None))).body shouldBe reload
    read(await(f.bare(page))).body shouldBe reload
    // None of them ended the view.
    read(await(f.poll(page, seen))).frame shouldBe Some(seen + 1L)
  }

  it should "get 410 when its cursor is older than what the page has confirmed" in {
    val f                         = new LongPollingFixture()
    val (page, seen, _, waiting)  = start(f)
    read(await(f.poll(page, seen - 1L))) shouldBe Answer(410, None, None, "", Some("no-cache"))
    waiting.isCompleted shouldBe false
  }

  "A message" should "get 410 before the first poll, from another page, with its cursor ahead, or after the view ended" in {
    val f    = new LongPollingFixture(idleTimeout = 10.seconds)
    val page = f.render()
    await(f.heartbeat(page, 0L)).status.code shouldBe 410
    val (initial, waiting) = frames(f, page, 0L)
    val seen               = initial.size.toLong
    // The page has every frame that was handed out: a message cannot claim more.
    await(f.heartbeat(page, seen + 1L)).status.code shouldBe 410
    await(f.publish(page.copy(id = UUID.randomUUID()), seen, """[6,"0"]""")).status.code shouldBe 410
    await(f.heartbeat(page, seen)).status.code shouldBe 200
    read(await(waiting)).frame shouldBe Some(seen + 1L)
    // The page polls no more: after the idle timeout the view ends.
    f.advance(21.seconds)
    f.hasView(page) shouldBe false
    await(f.heartbeat(page, seen)).status.code shouldBe 410
  }

  "A WebSocket" should "get nothing when a poll built the view, and closing it leaves the view" in {
    val f                      = new LongPollingFixture()
    val (page, seen, _, poll)  = start(f)
    val input                  = Queue[Future, String]()
    f.webSocket(page, input).value shouldBe Some(Success(SessionStart.Refused()))
    await(input.close())
    f.worker.drain()
    f.hasView(page) shouldBe true
    await(f.heartbeat(page, seen))
    read(await(poll)).frame shouldBe Some(seen + 1L)
  }

  "A render" should "make a fresh-page record, and an attach makes none" in {
    val f = new LongPollingFixture()
    f.sessions.freshPageRecords shouldBe 0
    val page = f.render()
    f.sessions.freshPageRecords shouldBe 1
    frames(f, page, 0L)
    f.sessions.freshPageRecords shouldBe 0
  }

  it should "leave its record and no state when its storage write fails, and the page is then told to reload" in {
    // The record is made before the state is stored, so no stored state is
    // without a record. A write that fails leaves a record without a state.
    val failing = new spoonbill.state.StateStorage[Future, Int] {
      private val underlying = new spoonbill.state.StateStorage.DefaultStateStorage[Future, Int](5000, allowDevMode = false)
      def exists(device: String, session: String): Future[Boolean] = underlying.exists(device, session)
      def get(device: String, session: String): Future[spoonbill.state.StateManager[Future]] = underlying.get(device, session)
      def create(device: String, session: String, state: Int): Future[spoonbill.state.StateManager[Future]] =
        Future.failed(new IllegalStateException("the state cannot be written"))
      def remove(device: String, session: String): Unit = underlying.remove(device, session)
    }
    val f = new LongPollingFixture(storage = Some(failing))
    val page = f.Page(spoonbill.Qsid("long-polling", java.util.UUID.randomUUID().toString))
    val rendered = f.sessions.initAppState(page.qsid, spoonbill.web.Request(spoonbill.web.Request.Method.Get, spoonbill.web.PathAndQuery.Root, Nil, None, ()))
    f.worker.drain()
    rendered.value.exists(_.isFailure) shouldBe true
    f.sessions.freshPageRecords shouldBe 1
    await(failing.exists(page.qsid.deviceId, page.qsid.sessionId)) shouldBe false
    // The first poll claims the record and finds no state.
    read(await(f.poll(page, 0L))).body shouldBe "[1]"
    f.hasView(page) shouldBe false
    f.sessions.freshPageRecords shouldBe 0
  }

  it should "end at most 100 expired records of pages that never connect at each render, and drop their states for good" in {
    // In memory whatever the development-mode switch of the JVM says: in that
    // mode a removed state stays in its file, and this case would prove nothing.
    val storage = new spoonbill.state.StateStorage.DefaultStateStorage[Future, Int](5000, allowDevMode = false)
    val f       = new LongPollingFixture(idleTimeout = 10.seconds, storage = Some(storage))
    val pages   = Vector.fill(250)(f.render())
    f.sessions.freshPageRecords shouldBe 250
    f.advance(11.seconds)
    val counts = Vector.fill(3) { f.render(); f.sessions.freshPageRecords }
    // Each render ends up to 100 records and makes its own.
    counts shouldBe Vector(151, 52, 3)
    // The default storage is local, so their states are dropped: not kept in
    // the for-deletion cache, which belongs to the states of views that retired.
    pages.foreach(page => await(storage.exists(page.qsid.deviceId, page.qsid.sessionId)) shouldBe false)
  }
}

/** The delivery itself, with frames that a case makes. */
class LongPollingDeliveryInterpreterSpec extends AnyFlatSpec with Matchers {
  import LongPollingDelivery.PollAnswer
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = scala.concurrent.Await.result(value, 5.seconds)

  private final class Fixture {
    val worker = new Worker
    val time   = new LongPollingFixture.ManualTime(worker)
    val frames = Queue[Future, Frontend.OutgoingFrame]()
    val delivery = new LongPollingDelivery[Future](5.seconds, time.poll, Reporter.PrintReporter)
    await(delivery.own(frames.stream))
    def poll(seen: Long): Future[PollAnswer] = { val answer = delivery.poll(seen); worker.drain(); answer }
  }

  "A kept command that has expired" should "be answered with a keep-alive frame under the same number and revision" in {
    val f       = new Fixture
    @volatile var awaited = true
    val first = f.poll(0L)
    f.frames.offerUnsafe(Frontend.OutgoingFrame("[4,1]", Frontend.FrameKind.Dom, () => true))
    f.worker.drain()
    await(first) shouldBe PollAnswer.Frame("[4,1]", 1L, 1L)
    val second = f.poll(1L)
    f.frames.offerUnsafe(Frontend.OutgoingFrame("[10,\"0\",\"code\"]", Frontend.FrameKind.Command, () => awaited))
    f.worker.drain()
    await(second) shouldBe PollAnswer.Frame("[10,\"0\",\"code\"]", 2L, 1L)
    // The answer was lost, and the command expired meanwhile.
    awaited = false
    await(f.poll(1L)) shouldBe PollAnswer.Frame(Frontend.KeepAliveMessage, 2L, 1L)
  }

  "The end of the frames" should "tell every poll that waits, and every later one, to reload" in {
    val f       = new Fixture
    val waiting = f.poll(0L)
    await(f.frames.close())
    f.worker.drain()
    await(waiting) shouldBe PollAnswer.Reload
    await(f.poll(0L)) shouldBe PollAnswer.Reload
  }

  "An abandoned entry" should "tell the polls that joined it to reload" in {
    val worker   = new Worker
    val time     = new LongPollingFixture.ManualTime(worker)
    val delivery = new LongPollingDelivery[Future](5.seconds, time.poll, Reporter.PrintReporter)
    val joined   = delivery.poll(0L)
    worker.drain()
    joined.isCompleted shouldBe false
    await(delivery.abandon())
    worker.drain()
    await(joined) shouldBe PollAnswer.Reload
  }

  /**
   * The timer of a poll comes back from the scheduler on another thread, and
   * the poll is answered meanwhile. Whichever comes first, the timer is
   * cancelled: none stays set for a poll that the machine has settled. A timer
   * that was kept by a separate look at the machine state failed this, about
   * 29 times in 20,000 polls.
   */
  "The timer of a poll" should "be cancelled once the poll is answered, also when it comes back after the answer" in {
    val scheduling = java.util.concurrent.Executors.newFixedThreadPool(4)
    try {
      val context = scala.concurrent.ExecutionContext.fromExecutor(scheduling)
      final class Timer extends Scheduler.JobHandler[Future, Unit] {
        val cancelled              = new java.util.concurrent.atomic.AtomicBoolean(false)
        def unsafeCancel(): Unit   = cancelled.set(true)
        def cancel(): Future[Unit] = Future.successful(unsafeCancel())
        def result: Future[Unit]   = Future.never
      }
      val timers = new java.util.concurrent.ConcurrentLinkedQueue[Future[Timer]]()
      val timing = new LongPollingDelivery.Timing[Future] {
        def scheduleOnce(delay: FiniteDuration)(job: => Future[Unit]): Future[Scheduler.JobHandler[Future, Unit]] = {
          val timer = Future(new Timer)(context)
          timers.add(timer)
          timer
        }
      }
      val frames   = Queue[Future, Frontend.OutgoingFrame]()
      val delivery = new LongPollingDelivery[Future](30.seconds, timing, Reporter.PrintReporter)
      await(delivery.own(frames.stream))
      val polls = 20000
      for (seen <- 0 until polls) {
        val answer = delivery.poll(seen.toLong)
        frames.offerUnsafe(Frontend.OutgoingFrame(s"[4,$seen]", Frontend.FrameKind.Dom, () => true))
        await(answer) shouldBe PollAnswer.Frame(s"[4,$seen]", seen + 1L, seen + 1L)
      }
      val made = timers.toArray(Array.empty[Future[Timer]]).toVector.map(await)
      made.size shouldBe polls
      // The callbacks of the last timers.
      val deadline = System.nanoTime() + 5.seconds.toNanos
      while (made.exists(!_.cancelled.get()) && System.nanoTime() < deadline) Thread.sleep(10)
      made.count(!_.cancelled.get()) shouldBe 0
    } finally scheduling.shutdownNow()
  }
}
