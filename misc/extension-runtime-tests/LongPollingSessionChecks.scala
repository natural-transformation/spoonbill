package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Try
import spoonbill.{Context, Extension, Qsid}
import spoonbill.data.Bytes
import spoonbill.effect.{AsyncTable, Effect, Reporter, Stream}
import spoonbill.server.*
import spoonbill.state.{StateManager, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Long-polling cases that need an effect that can be cancelled. `Future`
 * cannot be cancelled, so only the lazy-effect modules run them.
 */
object LongPollingSessionChecks {

  private def run[F[_]: Effect, A](effect: F[A]): A = Await.result(Effect[F].toFuture(effect), 10.seconds)

  private val quiet: Reporter = new Reporter {
    def error(message: String, error: Throwable): Unit                = ()
    def error(message: String): Unit                                  = ()
    def warning(message: String, error: Throwable): Unit              = ()
    def warning(message: String): Unit                                = ()
    def info(message: String): Unit                                   = ()
    def debug(message: String): Unit                                  = ()
    def debug(message: String, arg1: Any): Unit                       = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  }

  /** Asks for the cancellation from another thread: a step that puts it off makes it wait. */
  private def request(cancel: () => Unit)(implicit ec: ExecutionContext): Future[Unit] = {
    val cancelling = Future(cancel())
    Try(Await.ready(cancelling, 1.second))
    cancelling
  }

  /** What a check reads from a poll answer. */
  private final case class Answer(status: Int, frame: Option[Long], body: String)

  /**
   * One server with a counter page.
   *
   * @param setup
   *   what the extension of a view waits for in its setup
   * @param storage
   *   the state storage, when a check holds it
   */
  private final class Server[F[_]: Effect](
    setup: () => F[Unit],
    storage: Option[StateStorage[F, Int]] = None,
    clock: () => Long = () => System.nanoTime(),
    pollTiming: Option[LongPollingDelivery.Timing[F]] = None
  )(implicit
    ec: ExecutionContext
  ) {
    private val F       = Effect[F]
    val built           = new AtomicInteger()
    private val context = Context[F, Int, Any]
    import context.*
    private val config = SpoonbillServiceConfig[F, Int, Any](
      stateLoader = StateLoader.default(0),
      document = state => Html(body(button("Add", event("click")(access => access.transition(_ + 1))), p(s"count $state"))),
      reporter = quiet,
      stateStorage = storage.orNull,
      extensions = List(Extension[F, Int, Any]("held") { _ =>
        F.map(F.flatMap(F.delay(built.incrementAndGet()))(_ => setup()))(_ => Extension.Handlers[F, Int, Any]())
      })
    )
    val sessions  = new SessionsService(config, new PageService(config), nanoTime = clock)
    val messaging =
      new MessagingService[F](quiet, null.asInstanceOf[CommonService[F]], sessions, None, 30.seconds, pollTiming = pollTiming)
    val qsid      = Qsid("long-polling-checks", UUID.randomUUID().toString)
    val page      = UUID.randomUUID()

    private def request(path: String): Request[Unit] =
      Request(Request.Method.Get, PathAndQuery.fromString(path), Nil, None, ())
    def render(): F[Int] = sessions.initAppState(qsid, request("/"))
    def render(other: Qsid): F[Int] = sessions.initAppState(other, request("/"))
    def poll(seen: Long): F[HttpResponse[F]] =
      messaging.longPollingSubscribe(qsid, request(s"/bridge/long-polling/x/subscribe?page=$page&seen=$seen"))
    def heartbeat(seen: Long): F[HttpResponse[F]] =
      F.flatMap(Stream(Bytes.wrap("""[6,"0"]""".getBytes("UTF-8"))).mat[F]()) { body =>
        messaging.longPollingPublish(qsid, request(s"/bridge/long-polling/x/publish?page=$page&seen=$seen"), body)
      }
    def read(response: HttpResponse[F]): Answer = {
      val frame = response.headers.collectFirst { case (key, value) if key.equalsIgnoreCase("spoonbill-frame") => value.toLong }
      Answer(response.status.code, frame, run(response.body.fold(Bytes.empty)(_ ++ _)).asUtf8String)
    }

    /** Applies the frames that a new view sends at once, and returns the cursor of the page. */
    def settle(from: Long): Long = {
      var seen = from
      var next = Effect[F].toFuture(poll(seen))
      while (Try(Await.ready(next, 2.seconds)).isSuccess) {
        val answer = read(Await.result(next, 1.second))
        if (answer.frame.isEmpty) throw new AssertionError(s"Expected a frame: $answer")
        seen = answer.frame.get
        next = Effect[F].toFuture(poll(seen))
      }
      seen
    }
  }

  /**
   * A second poll joins a first attach, and the first poll is cancelled while
   * the attach is still in its setup. The attach runs to its end. The second
   * poll gets the frames of the one view, and no second view is built.
   */
  def cancelledFirstPoll[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F            = Effect[F]
    val setupStarted = new CountDownLatch(1)
    val release      = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val server = new Server[F](() => F.flatMap(F.delay(setupStarted.countDown()))(_ => release.get(())))
    run(server.render())
    val cancel = start(F.map(server.poll(0L))(_ => ()))
    try {
      assert(setupStarted.await(10, TimeUnit.SECONDS), "The setup never started")
      val joined     = Effect[F].toFuture(server.poll(0L))
      val cancelling = request(cancel)
      run(release.put((), ()))
      Await.result(cancelling, 10.seconds)
      val answer = server.read(Await.result(joined, 10.seconds))
      assert(answer.status == 200 && answer.frame.contains(1L), s"The joined poll got $answer")
      assert(server.built.get() == 1, s"Views built: ${server.built.get()}")
    } finally {
      Try(run(release.putEither((), Right(()), silent = true)))
      ()
    }
  }

  /**
   * A poll waits, its pull is under way, and the poll is cancelled. The frame
   * that comes is neither skipped nor handed out as two: the next poll of the
   * same cursor gets it, and a poll of the cursor after it waits.
   */
  def cancelledAfterPull[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F = Effect[F]
    // A poll that waits sets its timer right after it has registered and asked
    // for its frame, so the timer says when the pull is under way.
    val waits     = new java.util.concurrent.atomic.AtomicReference(new CountDownLatch(1))
    val scheduler = spoonbill.effect.Scheduler[F]
    val timing = new LongPollingDelivery.Timing[F] {
      def scheduleOnce(delay: FiniteDuration)(job: => F[Unit]): F[spoonbill.effect.Scheduler.JobHandler[F, Unit]] = {
        waits.get().countDown()
        scheduler.scheduleOnce(delay)(job)
      }
    }
    val server = new Server[F](() => F.unit, pollTiming = Some(timing))
    run(server.render())
    val seen = server.settle(0L)
    waits.set(new CountDownLatch(1))
    val cancel = start(F.map(server.poll(seen))(_ => ()))
    assert(waits.get().await(10, TimeUnit.SECONDS), "The poll never waited")
    Await.result(request(cancel), 10.seconds)
    assert(server.read(run(server.heartbeat(seen))).status == 200, "The heartbeat was not taken")
    val again = server.read(run(server.poll(seen)))
    assert(again.frame.contains(seen + 1L) && again.body == "[16]", s"The frame after the cancelled poll: $again")
    val later = Effect[F].toFuture(server.poll(seen + 1L))
    assert(Try(Await.ready(later, 1.second)).isFailure, "A poll of the next cursor got a frame: it was handed out twice")
  }

  /**
   * A render is cancelled after it made the fresh-page record of its page and
   * before the state is stored. The first poll of the page does not hang: it
   * gets a view or a reload, and no view is built without a stored state.
   */
  def cancelledRender[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F       = Effect[F]
    val entered = new CountDownLatch(1)
    val release = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val held = new StateStorage[F, Int] {
      private val underlying                                         = StateStorage[F, Int]()
      def exists(device: String, session: String): F[Boolean]        = underlying.exists(device, session)
      def get(device: String, session: String): F[StateManager[F]]   = underlying.get(device, session)
      def remove(device: String, session: String): Unit              = underlying.remove(device, session)
      def create(device: String, session: String, state: Int): F[StateManager[F]] =
        F.flatMap(F.delay(entered.countDown()))(_ => F.flatMap(release.get(()))(_ => underlying.create(device, session, state)))
    }
    val server = new Server[F](() => F.unit, Some(held))
    val cancel = start(F.map(server.render())(_ => ()))
    try {
      assert(entered.await(10, TimeUnit.SECONDS), "The render never stored")
      Await.result(request(cancel), 10.seconds)
      val first = server.read(run(server.poll(0L)))
      val stored = run(held.exists(server.qsid.deviceId, server.qsid.sessionId))
      if (stored) assert(first.frame.contains(1L), s"A stored state, and the first poll got $first")
      else {
        assert(first.body == "[1]", s"No stored state, and the first poll got $first")
        assert(server.built.get() == 0, "A view was built without a stored state")
      }
    } finally {
      Try(run(release.putEither((), Right(()), silent = true)))
      ()
    }
  }

  /**
   * A render is cancelled while its cleanup removes the state of a page that
   * expired. The cleanup runs to its end: the record of that page is not left
   * releasing, and no state stays live without a record or a view. What this
   * shows is the end state after a cancel that came during the removal. It
   * does not prove the `uncancelable` around the cleanup: the cleanup is one
   * `delay` block, and no effect type cancels a block that runs.
   */
  def cancelledCleanup[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F       = Effect[F]
    val now     = new AtomicInteger()
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val removed = new AtomicInteger()
    @volatile var holding = false
    val local = new StateStorage[F, Int] {
      private val underlying                                       = StateStorage[F, Int]()
      override private[spoonbill] def localToProcess: Boolean      = true
      def exists(device: String, session: String): F[Boolean]      = underlying.exists(device, session)
      def get(device: String, session: String): F[StateManager[F]] = underlying.get(device, session)
      def create(device: String, session: String, state: Int): F[StateManager[F]] = underlying.create(device, session, state)
      def remove(device: String, session: String): Unit = {
        if (holding) { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        removed.incrementAndGet()
        underlying.remove(device, session)
      }
    }
    // One second of the clock for each step; the lifetime is the idle timeout, 60 s.
    val server = new Server[F](() => F.unit, Some(local), () => now.get().toLong * 1000000000L)
    run(server.render())
    now.set(61)
    holding = true
    val later  = Qsid("long-polling-checks", UUID.randomUUID().toString)
    val cancel = start(F.map(server.render(later))(_ => ()))
    try {
      assert(entered.await(10, TimeUnit.SECONDS), "The cleanup never removed")
      val cancelling = request(cancel)
      release.countDown()
      Await.result(cancelling, 10.seconds)
      holding = false
      assert(removed.get() == 1, s"Removals: ${removed.get()}")
      // The expired record ended; the cancelled render kept its own record.
      assert(server.sessions.freshPageRecords == 1, s"Records: ${server.sessions.freshPageRecords}")
      run(server.render(Qsid("long-polling-checks", UUID.randomUUID().toString)))
      assert(server.sessions.freshPageRecords == 2, s"Records after a later render: ${server.sessions.freshPageRecords}")
    } finally {
      release.countDown()
      ()
    }
  }
}
