package spoonbill.server.internal.services

import avocet.Id
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.Success
import spoonbill.Router
import spoonbill.effect.{Effect, Queue, Stream}
import spoonbill.server.HttpResponse
import spoonbill.state.{StateManager, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.web.PathAndQuery

/**
 * An unguarded view confirms each navigation (procedure 24) only once the route
 * of its history event has been rendered and published, on a WebSocket and on
 * long polling, and says so in its first frame. Its page drops user events
 * until the confirmation, so a click on the display that the user left meets
 * the handlers of neither route. The render that publishes the route sends its
 * URL numbered with the navigation, and the confirmation follows its patch. A
 * route that fails, or whose render fails, retires the view: it runs no more
 * routes or callbacks, and its page is told to reload.
 */
class NavigationConfirmationSpec extends AnyFlatSpec with Matchers {
  import LongPollingFixture.*
  private given Effect[Future] = Effect.futureEffect

  /**
   * The routes of the page: `/n/<k>` is state k (`/n/05` too, whose URL is
   * `/n/5`), `/held/<k>` is state k once `release` completes, `/broken` fails,
   * `/held-broken` fails once `release` completes, and `/denied` is denied by
   * the router. `asked` has the navigations to `/n/<k>` and `/held-broken` that
   * the router was asked for.
   */
  private final class Routes {
    val release                                                  = Promise[Unit]()
    @volatile var entered                                        = 0
    @volatile var asked                                          = Vector.empty[String]
    private def stateOf(path: PathAndQuery, prefix: String): Int = path.mkString.stripPrefix(prefix).toInt
    val router: Router[Future, Int] = Router[Future, Int](
      { case state => PathAndQuery.fromString(s"/n/$state") },
      {
        case path if path.mkString.startsWith("/n/") =>
          _ => { asked :+= path.mkString; Future.successful(stateOf(path, "/n/")) }
        case path if path.mkString.startsWith("/held/") =>
          _ => { entered += 1; release.future.map(_ => stateOf(path, "/held/"))(ExecutionContext.parasitic) }
        case path if path.mkString == "/broken" => _ => Future.failed(new IllegalStateException("A broken route"))
        // Fails once `release` completes: a second navigation can wait behind it.
        case path if path.mkString == "/held-broken" =>
          _ => {
            asked :+= "/held-broken"
            release.future
              .flatMap(_ => Future.failed(new IllegalStateException("A broken route")))(ExecutionContext.parasitic)
          }
        // A router that denies the navigation itself, on a view that is live.
        case path if path.mkString == "/denied" => _ => Future.failed(new spoonbill.server.SessionAccessDenied)
      }
    )
  }

  /**
   * The default storage, which keeps the state manager that it gave out last,
   * so that a case can write a state as another fiber of the application would.
   */
  private final class RecordingStorage extends StateStorage[Future, Int] {
    private val underlying                                              = StateStorage[Future, Int]()
    @volatile var manager                                               = Option.empty[StateManager[Future]]
    override private[spoonbill] def localToProcess: Boolean             = true
    private def keep(value: StateManager[Future]): StateManager[Future] = { manager = Some(value); value }
    def exists(device: String, session: String): Future[Boolean]        = underlying.exists(device, session)
    def get(device: String, session: String): Future[StateManager[Future]] =
      underlying.get(device, session).map(keep)(ExecutionContext.parasitic)
    def create(device: String, session: String, state: Int): Future[StateManager[Future]] =
      underlying.create(device, session, state).map(keep)(ExecutionContext.parasitic)
    def remove(device: String, session: String): Unit = underlying.remove(device, session)
  }

  /**
   * The Future effect, which can hold the next result of `promiseF`: the waiter
   * of a navigation's turn, so that the history fiber stops after the render of
   * the route until the case releases it. Everything else is the Future effect
   * itself.
   */
  private final class HoldingEffect extends Effect[Future] {
    // A def: the trait's initializer calls `pure` before the fields of this class exist.
    private def base: Effect[Future] = Effect.futureEffect
    @volatile private var arm        = false
    @volatile private var waiting    = Option.empty[() => Unit]

    /** Holds the next result of `promiseF`. */
    def holdNext(): Unit = arm = true

    /** Whether a result is held. */
    def holding: Boolean = waiting.nonEmpty

    /** Gives the held result to its waiter. */
    def release(): Unit = { val held = waiting; waiting = None; held.foreach(_()) }

    def promiseF[A](cb: (Either[Throwable, A] => Unit) => Future[Unit]): Future[A] =
      if (!arm) base.promiseF(cb)
      else {
        arm = false
        val later = Promise[A]()
        base
          .promiseF(cb)
          .onComplete(result =>
            waiting = Some { () =>
              later.complete(result); ()
            }
          )(ExecutionContext.parasitic)
        later.future
      }

    def pure[A](value: A): Future[A]                                                     = base.pure(value)
    def delay[A](value: => A): Future[A]                                                 = base.delay(value)
    def uncancelable[A](task: => Future[A]): Future[A]                                   = base.uncancelable(task)
    def fail[A](e: Throwable): Future[A]                                                 = base.fail(e)
    def unit: Future[Unit]                                                               = base.unit
    def never[T]: Future[T]                                                              = base.never
    def fromTry[A](value: => scala.util.Try[A]): Future[A]                               = base.fromTry(value)
    def promise[A](cb: (Either[Throwable, A] => Unit) => Unit): Future[A]                = base.promise(cb)
    def flatMap[A, B](m: Future[A])(f: A => Future[B]): Future[B]                        = base.flatMap(m)(f)
    def map[A, B](m: Future[A])(f: A => B): Future[B]                                    = base.map(m)(f)
    def recover[A, AA >: A](m: Future[A])(f: PartialFunction[Throwable, AA]): Future[AA] = base.recover(m)(f)
    def recoverF[A, AA >: A](m: Future[A])(f: PartialFunction[Throwable, Future[AA]]): Future[AA] =
      base.recoverF(m)(f)
    def start[A](create: => Future[A])(implicit ec: ExecutionContext): Future[Effect.Fiber[Future, A]] =
      base.start(create)
    def blocking[T](f: => T)(implicit ec: ExecutionContext): Future[T]          = base.blocking(f)
    def fork[A](m: => Future[A])(implicit ec: ExecutionContext): Future[A]      = base.fork(m)
    def sequence[A](in: List[Future[A]]): Future[List[A]]                       = base.sequence(in)
    def runAsync[A](m: Future[A])(callback: Either[Throwable, A] => Unit): Unit = base.runAsync(m)(callback)
    def run[A](m: Future[A]): Either[Throwable, A]                              = base.run(m)
    def toFuture[A](m: Future[A]): Future[A]                                    = base.toFuture(m)
  }

  /**
   * A frame without the space that the encoder writes before its closing
   * bracket.
   */
  private def compact(frame: String): String = frame.replaceAll(" \\]$", "]")

  /** Reads the frames of a view as they come, on the fixture's worker. */
  private final class Frames(f: LongPollingFixture, output: Stream[Future, String]) {
    private var waiting = Option.empty[Future[Option[String]]]

    /** The frames that the view has sent since the last read. */
    def read(): Vector[String] = {
      var frames = Vector.empty[String]
      var more   = true
      while (more) {
        val next = waiting.getOrElse(output.pull())
        f.worker.drain()
        next.value match {
          case Some(Success(Some(frame))) => frames :+= compact(frame); waiting = None
          case Some(_)                    => more = false; waiting = None
          case None                       => more = false; waiting = Some(next)
        }
      }
      frames
    }
  }

  /** A WebSocket view of a new page: its input, and its frames. */
  private def webSocket(f: LongPollingFixture): (Queue[Future, String], Frames) = {
    val (_, input, output) = webSocketPage(f)
    (input, output)
  }

  /** A WebSocket view of a new page: the page, its input, and its frames. */
  private def webSocketPage(f: LongPollingFixture): (f.Page, Queue[Future, String], Frames) = {
    val page  = f.render()
    val input = Queue[Future, String]()
    f.webSocket(page, input).value match {
      case Some(Success(SessionStart.Attached(output, _))) => (page, input, new Frames(f, output))
      case other                                           => fail(s"The WebSocket got no view: $other")
    }
  }

  /** A message of the page on its WebSocket. */
  private def send(f: LongPollingFixture, input: Queue[Future, String], message: String): Unit = {
    input.offerUnsafe(message) shouldBe true
    f.worker.drain()
  }

  /**
   * The answers of a long-polling page that applies every frame, until a poll
   * waits.
   */
  private def frames(
    f: LongPollingFixture,
    page: f.Page,
    from: Long
  ): (Vector[Answer], Future[HttpResponse[Future]]) = {
    var seen    = from
    var answers = Vector.empty[Answer]
    var next    = f.poll(page, seen)
    while (next.isCompleted) {
      val raw    = read(await(next))
      val answer = raw.copy(body = compact(raw.body))
      answers :+= answer
      seen = answer.frame.getOrElse(fail(s"Not a frame: $answer"))
      next = f.poll(page, seen)
    }
    (answers, next)
  }

  "An unguarded WebSocket view" should "say in its first frame that it confirms navigations" in {
    val f           = new LongPollingFixture(router = new Routes().router)
    val (_, output) = webSocket(f)
    output.read().head shouldBe "[17,true]"
  }

  it should "confirm a navigation only once its route has published its patch" in {
    val routes          = new Routes
    val f               = new LongPollingFixture(router = routes.router)
    val (input, output) = webSocket(f)
    output.read()
    send(f, input, """[3,"/held/5"]""")
    routes.entered shouldBe 1
    // The route waits: nothing is rendered, and nothing is confirmed.
    output.read() shouldBe empty
    routes.release.success(())
    f.worker.drain()
    val after        = output.read()
    val patch        = after.indexWhere(_.startsWith("[4,"))
    val confirmation = after.indexOf("[24,1]")
    withClue(after) {
      patch should be >= 0
      confirmation should be > patch
    }
  }

  it should "confirm a navigation only once a delayed render has published its patch" in {
    val f               = new LongPollingFixture(router = new Routes().router, delayedRender = 1.second)
    val (input, output) = webSocket(f)
    output.read()
    send(f, input, """[3,"/n/5"]""")
    // The state is written, and its render waits a second: nothing may confirm the navigation yet.
    val early = output.read()
    withClue(early)(early.exists(_.startsWith("[24,")) shouldBe false)
    var after = early
    val end   = System.nanoTime() + 5.seconds.toNanos
    while (!after.contains("[24,1]") && System.nanoTime() < end) {
      Thread.sleep(50)
      f.worker.drain()
      after ++= output.read()
    }
    withClue(after) {
      after.indexOf("[24,1]") should be > after.indexWhere(_.startsWith("[4,"))
      after.indexWhere(_.startsWith("[4,")) should be >= 0
    }
  }

  it should "confirm a navigation whose route leaves the page as it is, after its empty patch" in {
    val f               = new LongPollingFixture(router = new Routes().router)
    val (input, output) = webSocket(f)
    output.read()
    send(f, input, """[3,"/n/0"]""")
    val after = output.read()
    withClue(after) {
      // The render of the same state publishes a patch with no change.
      after.exists(_.startsWith("[4,")) shouldBe false
      after.indexOf("[24,1]") should be > after.indexOf("[4]")
      after.indexOf("[4]") should be >= 0
    }
  }

  it should "confirm navigations one after another, in the order in which they came" in {
    val routes          = new Routes
    val f               = new LongPollingFixture(router = routes.router)
    val (input, output) = webSocket(f)
    output.read()
    // Back, and Forward before Back's route is published.
    send(f, input, """[3,"/held/1"]""")
    send(f, input, """[3,"/n/2"]""")
    output.read().filter(_.startsWith("[24,")) shouldBe empty
    routes.release.success(())
    f.worker.drain()
    val after = output.read()
    withClue(after) {
      after.filter(_.startsWith("[24,")) shouldBe Vector("[24,1]", "[24,2]")
      after.indexOf("[24,1]") should be < after.lastIndexWhere(_.startsWith("[4,"))
      after.last shouldBe "[24,2]"
    }
    f.reports.errors shouldBe empty
  }

  it should "tell its page to reload when a route fails, and confirm nothing" in {
    val f               = new LongPollingFixture(router = new Routes().router)
    val (input, output) = webSocket(f)
    output.read()
    send(f, input, """[3,"/broken"]""")
    val after = output.read()
    withClue(after) {
      after should contain("[1]")
      after.exists(_.startsWith("[24,")) shouldBe false
    }
    f.reports.errors.map(_._1) shouldBe Vector("Unable to route a history event")
  }

  it should "route nothing more once a route has failed, and still tell its page to reload" in {
    // The code review of 2026-11-24, finding 6: Forward's route ran on the
    // view whose route had failed, and confirmed it, before the page read the
    // reload.
    val routes          = new Routes
    val f               = new LongPollingFixture(router = routes.router)
    val (input, output) = webSocket(f)
    output.read()
    send(f, input, """[3,"/held-broken"]""")
    send(f, input, """[3,"/n/2"]""")
    routes.release.success(())
    f.worker.drain()
    val after = output.read()
    withClue(after) {
      after should contain("[1]")
      after.exists(_.startsWith("[24,")) shouldBe false
    }
    routes.asked shouldBe Vector("/held-broken")
    f.reports.errors.map(_._1) shouldBe Vector("Unable to route a history event")
  }

  it should "tell its page to reload when its own router denies a navigation" in {
    // The code review of 2026-11-24, finding 6: a denial of a live router was
    // taken for the end of the view, and the page got no recovery signal.
    val f               = new LongPollingFixture(router = new Routes().router)
    val (input, output) = webSocket(f)
    output.read()
    send(f, input, """[3,"/denied"]""")
    val after = output.read()
    withClue(after)(after should contain("[1]"))
    f.reports.errors.map(_._1) shouldBe Vector("Unable to route a history event")
  }

  it should "tell its page to reload when the render of a route is denied, and route nothing more" in {
    // The re-review of 2026-11-24: a denial that the application's render
    // threw was taken for the end of the view. The page got no recovery, and
    // the next navigation was routed and written on the same view.
    val routes          = new Routes
    val f               = new LongPollingFixture(router = routes.router, deniedState = Some(7))
    val (input, output) = webSocket(f)
    output.read()
    send(f, input, """[3,"/n/7"]""")
    send(f, input, """[3,"/n/8"]""")
    val after = output.read()
    withClue(after) {
      after should contain("[1]")
      after.exists(_.startsWith("[24,")) shouldBe false
    }
    routes.asked shouldBe Vector("/n/7")
    f.reports.errors.map(_._1) should contain theSameElementsAs
      Vector("Unable to process state update", "Unable to route a history event")
  }

  it should "run no callback once a route has failed, while its page still gets the reload" in {
    // The re-review of 2026-11-24: retirement stopped the routes that waited,
    // but a callback that came after the failure still ran. The output stays
    // unread until the end, as a page that has not read the reload yet.
    val f                     = new LongPollingFixture(router = new Routes().router)
    val (page, input, output) = webSocketPage(f)
    output.read()
    @volatile var calls = Vector.empty[String]
    val registered = f.view(page).topLevelComponentInstance.browserAccess.registerCallback("probe") { arg =>
      Future.successful(calls :+= arg)
    }
    f.worker.drain()
    await(registered)
    send(f, input, """[1,"probe:before"]""")
    send(f, input, """[3,"/broken"]""")
    send(f, input, """[1,"probe:after"]""")
    calls shouldBe Vector("before")
    output.read() should contain("[1]")
    f.reports.errors.map(_._1) shouldBe Vector("Unable to route a history event")
  }

  it should "number the URL of the render that publishes a route, and confirm the navigation right after its patch" in {
    // The re-review of 2026-11-24: the page skipped every URL while it waited,
    // the one of the route that won too, so the tab kept the alias that the
    // user had opened. The third review: the numbered URL then came in a
    // frame of its own after the render, which an older client took as one
    // more URL. The route of `/n/05` is state 5, whose URL is `/n/5`.
    val f               = new LongPollingFixture(router = new Routes().router)
    val (input, output) = webSocket(f)
    output.read()
    send(f, input, """[3,"/n/05"]""")
    val after = output.read()
    withClue(after) {
      val url = after.indexOf("""[6,"/n/5",1]""")
      url should be >= 0
      after(url + 1) should startWith("[4,")
      after(url + 2) shouldBe "[24,1]"
      after.count(_.startsWith("[6,")) shouldBe 1
    }
  }

  it should "pair the numbered URL with the render of the route, also when a state is written right after it" in {
    // The third review of 2026-11-24: the view read the state again after the
    // render of the route, so a state written in between (8) gave its URL to
    // the navigation while the page showed the route (5). A later render
    // shows the state written in between, with its own URL.
    val storage           = new RecordingStorage
    @volatile var between = true
    val f = new LongPollingFixture(
      router = new Routes().router,
      storage = Some(storage),
      rendering = state =>
        if (state == 5 && between) {
          between = false
          // Another fiber of the application writes 8 while the route renders.
          storage.manager.foreach(manager => await(manager.write(Id.TopLevel, 8)))
        }
    )
    val (page, input, output) = webSocketPage(f)
    output.read()
    send(f, input, """[3,"/n/5"]""")
    val route = output.read()
    withClue(route) {
      val url = route.indexOf("""[6,"/n/5",1]""")
      url should be >= 0
      route(url + 1) should (startWith("[4,") and include("count 5"))
      route(url + 2) shouldBe "[24,1]"
      route.exists(_.startsWith("""[6,"/n/8"""")) shouldBe false
    }
    val later = f.view(page).topLevelComponentInstance.browserAccess.transition((state: Int) => state)
    f.worker.drain()
    await(later)
    val after = output.read()
    withClue(after) {
      after should contain("""[6,"/n/8"]""")
      after.exists(frame => frame.startsWith("[4,") && frame.contains("count 8")) shouldBe true
    }
  }

  it should "send no URL for a path that its router does not route, and still confirm the navigation" in {
    // A path that the router does not route changes nothing, so the entry
    // that the user chose keeps its URL, as before the numbered URL.
    val f               = new LongPollingFixture(router = new Routes().router)
    val (input, output) = webSocket(f)
    output.read()
    send(f, input, """[3,"/elsewhere"]""")
    val after = output.read()
    withClue(after) {
      after should contain("[24,1]")
      after.exists(_.startsWith("[6,")) shouldBe false
    }
    f.reports.errors shouldBe empty
  }

  it should "confirm a navigation in the render of its route, before a later render, while the history fiber waits" in {
    // The fourth review of 2026-11-24: with the Future effect a continuation
    // runs where its promise completes, so a confirmation that the history
    // fiber sent after the render came at the same place, and no case told the
    // two apart. Here the history fiber waits after the render of the route,
    // as on an effect whose fibers take turns, while a later render runs: the
    // confirmation is out before the later render's URL.
    val effect                = new HoldingEffect
    val f                     = new LongPollingFixture(router = new Routes().router, effect = effect)
    val (page, input, output) = webSocketPage(f)
    output.read()
    effect.holdNext()
    send(f, input, """[3,"/n/5"]""")
    effect.holding shouldBe true
    val later = f.view(page).topLevelComponentInstance.browserAccess.transition((_: Int) => 8)
    f.worker.drain()
    await(later)
    val frames = output.read()
    withClue(frames) {
      effect.holding shouldBe true
      frames should contain("""[6,"/n/8"]""")
      val confirmation = frames.indexOf("[24,1]")
      confirmation should be >= 0
      confirmation should be < frames.indexOf("""[6,"/n/8"]""")
    }
    // The history fiber goes on, and confirms nothing more.
    effect.release()
    f.worker.drain()
    val after = output.read()
    withClue(after)(after.exists(_.startsWith("[24,")) shouldBe false)
  }

  "A long-polling view" should "confirm a navigation in the frame after the patch of its route" in {
    val f                  = new LongPollingFixture(router = new Routes().router)
    val page               = f.render()
    val (initial, waiting) = frames(f, page, 0L)
    initial.head.body shouldBe "[17,true]"
    val seen = initial.size.toLong
    val dom  = initial.count(_.body.startsWith("[4")).toLong
    await(f.navigate(page, seen, dom, "/n/3")).status.code shouldBe 200
    val raw          = read(await(waiting))
    val first        = raw.copy(body = compact(raw.body))
    val (rest, _)    = frames(f, page, first.frame.getOrElse(fail(s"Not a frame: $first")))
    val after        = first +: rest
    val patch        = after.indexWhere(_.body.startsWith("[4"))
    val confirmation = after.indexWhere(_.body == "[24,1]")
    withClue(after) {
      patch should be >= 0
      confirmation should be > patch
      // The page counts the patch before it reads the confirmation.
      after(confirmation).dom shouldBe Some(dom + 1L)
    }
  }

  it should "number the URL of the render that publishes a route, and confirm the navigation in the frame after its patch" in {
    // The re-review and the third review of 2026-11-24, on long polling.
    val f                  = new LongPollingFixture(router = new Routes().router)
    val page               = f.render()
    val (initial, waiting) = frames(f, page, 0L)
    val seen               = initial.size.toLong
    val dom                = initial.count(_.body.startsWith("[4")).toLong
    await(f.navigate(page, seen, dom, "/n/03")).status.code shouldBe 200
    val raw       = read(await(waiting))
    val first     = raw.copy(body = compact(raw.body))
    val (rest, _) = frames(f, page, first.frame.getOrElse(fail(s"Not a frame: $first")))
    val bodies    = (first +: rest).map(_.body)
    withClue(bodies) {
      val url = bodies.indexOf("""[6,"/n/3",1]""")
      url should be >= 0
      bodies(url + 1) should startWith("[4")
      bodies(url + 2) shouldBe "[24,1]"
      bodies.count(_.startsWith("[6,")) shouldBe 1
    }
  }

  it should "retire, and tell its page to reload, when a route fails" in {
    val f                  = new LongPollingFixture(router = new Routes().router)
    val page               = f.render()
    val (initial, waiting) = frames(f, page, 0L)
    val seen               = initial.size.toLong
    val dom                = initial.count(_.body.startsWith("[4")).toLong
    await(f.navigate(page, seen, dom, "/broken")).status.code shouldBe 200
    read(await(waiting)).body shouldBe "[1]"
    f.hasView(page) shouldBe false
    f.reports.errors.map(_._1) shouldBe Vector("Unable to route a history event")
  }
}
