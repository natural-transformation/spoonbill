package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}
import spoonbill.{Extension, Qsid}
import spoonbill.effect.{Effect, Queue}
import spoonbill.internal.ExtensionRuntime
import spoonbill.internal.TimerTestSupport.{Reports, Worker}
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.{StateManager, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * An attach of an unguarded session that fails is rolled back. Its failure is
 * the answer for the attaches that waited for it, and for no attach after
 * them: the page reconnects, and that attach builds a view of its own.
 */
class LegacyFailedAttachSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  /**
   * One unguarded session with two extensions. The worker runs the tasks of
   * the service when a case drains it.
   *
   * @param setup
   *   what the first extension waits for in its setup. The service looks for
   *   a closed connection before every setup and once after the last one, so
   *   one extension would do. The second one is there to be left out.
   * @param storage
   *   the state storage, when a case needs one that misbehaves
   */
  private final class Fixture(
    setup: () => Future[Unit] = () => Future.unit,
    storage: Option[StateStorage[Future, Int]] = None
  ) {
    val worker = new Worker
    private val config = SpoonbillServiceConfig[Future, Int, Any](
      stateLoader = StateLoader.default(0),
      document = state => Html(body(state.toString)),
      reporter = new Reports,
      stateStorage = storage.orNull,
      extensions = List(
        Extension[Future, Int, Any]("first")(_ =>
          summon[Effect[Future]].map(setup())(_ => Extension.Handlers[Future, Int, Any]())
        ),
        Extension.pure[Future, Int, Any]("second")(_ => Extension.Handlers[Future, Int, Any]())
      )
    )(worker)
    private val service = new SessionsService(config, new PageService(config))
    private val qsid    = Qsid("failed-attach", UUID.randomUUID().toString)
    private val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())

    /** One attach, with the socket that it comes through. */
    def attach(socket: Queue[Future, String] = Queue[Future, String]()): Future[SessionStart[Future]] = {
      val start = service.createAppIfNeeded(qsid, request, socket.stream)
      worker.drain()
      start
    }

    /** Whether the session has a view that an attach can use. */
    def registered: Boolean = await(service.getApp(qsid)).nonEmpty

    /** Checks that the attach got a view, and gives the view back. */
    def attachedAndReleased(start: Future[SessionStart[Future]]): Unit = start.value match {
      case Some(Success(SessionStart.Attached(_, release))) =>
        registered shouldBe true
        await(release())
        worker.drain()
      case other => fail(s"The attach got no view: $other")
    }
  }

  /** A storage whose first read of a state fails. */
  private final class FailsOnce(failure: Throwable) extends StateStorage[Future, Int] {
    private val underlying = StateStorage[Future, Int]()
    private var reads      = 0
    def exists(device: String, session: String): Future[Boolean] = underlying.exists(device, session)
    def create(device: String, session: String, state: Int): Future[StateManager[Future]] =
      underlying.create(device, session, state)
    def remove(device: String, session: String): Unit = underlying.remove(device, session)
    def get(device: String, session: String): Future[StateManager[Future]] = {
      reads += 1
      if (reads == 1) Future.failed(failure) else underlying.get(device, session)
    }
  }

  "An attach after one that failed before it had a view" should "build a new view" in {
    // The state cannot be read the first time. Nothing was built then, so
    // there is nothing to retire. The failure still must not stay behind.
    val failure = new IllegalStateException("the state cannot be read")
    val fixture = new Fixture(storage = Some(new FailsOnce(failure)))
    fixture.attach().value shouldBe Some(Failure(failure))
    fixture.registered shouldBe false

    fixture.attachedAndReleased(fixture.attach())
  }

  "An attach after one that was stopped during its setup" should "build a new view" in {
    val gate    = Promise[Unit]()
    val fixture = new Fixture(setup = () => gate.future)
    val socket  = Queue[Future, String]()
    val first   = fixture.attach(socket)
    // The socket closes while the first extension is still in its setup.
    await(socket.close())
    fixture.worker.drain()
    gate.success(())
    fixture.worker.drain()
    first.value.flatMap(_.failed.toOption).get shouldBe a[ExtensionRuntime.SetupAborted]
    fixture.registered shouldBe false

    // The page reconnects with a new socket. The setup returns at once now.
    fixture.attachedAndReleased(fixture.attach())
  }

  "An attach after one whose setup failed" should "run the setup again" in {
    var setups  = 0
    val failure = new IllegalStateException("not this time")
    val fixture = new Fixture(setup = () => { setups += 1; if (setups == 1) Future.failed(failure) else Future.unit })
    fixture.attach().value shouldBe Some(Failure(failure))
    fixture.registered shouldBe false

    fixture.attachedAndReleased(fixture.attach())
    setups shouldBe 2
  }

  "An attach that waits for one that fails" should "get its error, and leave a view to the attach after it" in {
    var setups  = 0
    val gate    = Promise[Unit]()
    val failure = new IllegalStateException("not this time")
    val fixture = new Fixture(setup = () => { setups += 1; if (setups == 1) gate.future else Future.unit })
    val first   = fixture.attach()
    val second  = fixture.attach()
    // The second attach waits for the first one, which is still in its setup.
    second.isCompleted shouldBe false

    gate.failure(failure)
    fixture.worker.drain()

    first.value shouldBe Some(Failure(failure))
    second.value shouldBe Some(Failure(failure))
    fixture.registered shouldBe false
    fixture.attachedAndReleased(fixture.attach())
    // The second attach ran no setup of its own: it took the answer of the first.
    setups shouldBe 2
  }
}
