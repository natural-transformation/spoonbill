package spoonbill.server.internal.services

import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.concurrent.TrieMap
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.util.Success
import spoonbill.{Metrics, Qsid, Router}
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.internal.TimerTestSupport.Worker
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * An unguarded attach never builds a state. When the stored state of its
 * session is missing, the page is told to reload: it loads again, is rendered
 * from its own address, and stores a new state. ADR 0006 records the decision
 * and its cost.
 */
class MissingStateReloadSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  private val missing = "the page is told to reload"

  /** Records the debug lines of one service. */
  private final class Recording extends Reporter {
    @volatile var debugs = Vector.empty[String]
    @volatile var errors = Vector.empty[String]
    def error(message: String, cause: Throwable): Unit                = synchronized(errors :+= message)
    def error(message: String): Unit                                  = synchronized(errors :+= message)
    def warning(message: String, cause: Throwable): Unit              = ()
    def warning(message: String): Unit                                = ()
    def info(message: String): Unit                                   = ()
    def debug(message: String): Unit                                  = synchronized(debugs :+= message)
    def debug(message: String, arg1: Any): Unit                       = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
    def reloads: Int = debugs.count(_.contains(missing))
  }

  /** A repository in memory, under a storage of `StateStorage.fromRepository`. */
  private final class Repository {
    val rows = TrieMap.empty[String, TrieMap[Id, Array[Byte]]]
    val storage: StateStorage[Future, String] = StateStorage.fromRepository[Future, String](
      repositoryExists = key => Future.successful(rows.contains(key)),
      loadSnapshot = key => Future.successful(rows.getOrElse(key, TrieMap.empty).toMap),
      createSnapshot = (key, snapshot) => Future.successful { rows.put(key, TrieMap.from(snapshot)); () },
      writeNode = (key, node, bytes) => Future.successful { rows.getOrElseUpdate(key, TrieMap.empty).put(node, bytes); () },
      deleteNode = (key, node) => Future.successful { rows.get(key).foreach(_.remove(node)); () },
      removeRepository = key => Future.successful { rows.remove(key); () }
    )
  }

  /**
   * One service of an application with a route `/orders/<id>`. The state
   * loader gives the home page; the router makes the order of the address.
   */
  private final class Fixture(storage: Option[StateStorage[Future, String]] = None) {
    val worker   = new Worker
    val reporter = new Recording
    val loads    = new AtomicInteger()
    private val order = "/orders/([0-9]+)".r
    private val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("the home page").andThenLoaded(loads),
      router = Router[Future, String](
        PartialFunction.empty,
        { case path if order.matches(path.mkString) =>
            _ => Future.successful(s"order ${order.findFirstMatchIn(path.mkString).get.group(1)}")
        }
      ),
      document = state => Html(body(div(state))),
      reporter = reporter,
      stateStorage = storage.orNull
    )(worker)
    val service = new SessionsService(config, new PageService(config))
    val qsid    = Qsid("missing-state", UUID.randomUUID().toString)
    private val bridge = Request(Request.Method.Get, PathAndQuery.fromString("/bridge/web-socket/session"), Nil, None, ())

    def render(path: String): String = {
      val rendered = service.initAppState(qsid, Request(Request.Method.Get, PathAndQuery.fromString(path), Nil, None, ()))
      worker.drain()
      await(rendered)
    }

    def attach(): Future[SessionStart[Future]] = {
      val start = service.createAppIfNeeded(qsid, bridge, Queue[Future, String]().stream)
      worker.drain()
      start
    }

    def registered: Boolean = { val app = service.getApp(qsid); worker.drain(); await(app).nonEmpty }

    /** The state of the view that the session has now. */
    def viewState: Option[String] = {
      val app = service.getApp(qsid)
      worker.drain()
      await(app).flatMap { view =>
        val read = view.topLevelComponentInstance.browserAccess.state
        worker.drain()
        Some(await(read))
      }
    }
  }

  extension (loader: StateLoader[Future, String]) {
    /** Counts the states that the loader makes. */
    private def andThenLoaded(loads: AtomicInteger): StateLoader[Future, String] =
      (device, request) => { loads.incrementAndGet(); loader(device, request) }
  }

  private def isReload(start: Future[SessionStart[Future]]): Boolean = start.value match {
    case Some(Success(SessionStart.Reload())) => true
    case _                                    => false
  }

  "A page rendered on a route whose stored state is gone" should "be told to reload at its first attach" in {
    val repository = new Repository
    val fixture    = new Fixture(Some(repository.storage))
    val before     = Metrics.MissingStateReloads.get
    fixture.render("/orders/42") shouldBe "order 42"
    // The row is deleted before the page connects: by another server, or by a
    // cleanup of the repository.
    repository.storage.remove(fixture.qsid.deviceId, fixture.qsid.sessionId)
    repository.rows shouldBe empty

    val start = fixture.attach()
    withClue(start.value)(isReload(start) shouldBe true)
    fixture.registered shouldBe false
    // The state loader ran once, for the render. No state was built for the
    // bridge request, which matches no route: it would be the home page.
    fixture.loads.get() shouldBe 1
    repository.rows shouldBe empty
    fixture.reporter.reloads shouldBe 1
    Metrics.MissingStateReloads.get - before should be >= 1L
  }

  "A WebSocket page whose socket closed" should "reload at its reconnect when a repository holds its state" in {
    val repository = new Repository
    val fixture    = new Fixture(Some(repository.storage))
    fixture.render("/orders/42")
    fixture.attach().value match {
      case Some(Success(SessionStart.Attached(_, release))) =>
        await(release())
        fixture.worker.drain()
      case other => fail(s"The first attach got no view: $other")
    }
    // The retirement of the view deleted the row.
    fixture.registered shouldBe false
    repository.rows shouldBe empty

    isReload(fixture.attach()) shouldBe true
    fixture.reporter.reloads shouldBe 1
  }

  it should "go on from its state with the default storage" in {
    val fixture = new Fixture()
    fixture.render("/orders/42")
    fixture.attach().value match {
      case Some(Success(SessionStart.Attached(_, release))) =>
        await(release())
        fixture.worker.drain()
      case other => fail(s"The first attach got no view: $other")
    }
    fixture.registered shouldBe false

    fixture.attach().value match {
      case Some(Success(SessionStart.Attached(_, _))) => fixture.viewState shouldBe Some("order 42")
      case other                                      => fail(s"The reconnect got no view: $other")
    }
    fixture.reporter.reloads shouldBe 0
  }

  "Two attaches of a session without a stored state" should "both be told to reload, and store nothing" in {
    val repository = new Repository
    val fixture    = new Fixture(Some(repository.storage))
    val first      = fixture.service.createAppIfNeeded(fixture.qsid, Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ()), Queue[Future, String]().stream)
    val second     = fixture.service.createAppIfNeeded(fixture.qsid, Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ()), Queue[Future, String]().stream)
    fixture.worker.drain()
    isReload(first) shouldBe true
    isReload(second) shouldBe true
    repository.rows shouldBe empty
    fixture.registered shouldBe false
    fixture.loads.get() shouldBe 0
    fixture.reporter.reloads shouldBe 2
  }

  "An attach that finds its state" should "not count as a reload for a missing state" in {
    val fixture = new Fixture()
    fixture.render("/orders/7")
    fixture.attach().value match {
      case Some(Success(SessionStart.Attached(_, _))) => fixture.viewState shouldBe Some("order 7")
      case other                                      => fail(s"The attach got no view: $other")
    }
    fixture.reporter.reloads shouldBe 0
  }
}
