package spoonbill.server.internal.services

import LongPollingFixture.*
import avocet.Id
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Queue, Reporter, Stream}
import spoonbill.internal.ExtensionRuntime
import spoonbill.internal.TimerTestSupport.Reports
import spoonbill.state.{StateManager, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.web.Response

/**
 * What passes from the view of one opening to the view of the next, and what
 * does not. The state of the session passes on. A request of an opening that
 * was replaced gets no view. A view that failed is not kept.
 */
class LongPollingHandoffSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect

  /** What a database would hold for the sessions, and how often the storage asked it to delete one. */
  private final class Repository {
    val entries  = TrieMap.empty[String, Map[Id, Array[Byte]]]
    var removals = 0
    val storage: StateStorage[Future, Int] = StateStorage.fromRepository[Future, Int](
      repositoryExists = key => Future.successful(entries.contains(key)),
      loadSnapshot = key => Future.successful(entries.getOrElse(key, Map.empty)),
      createSnapshot = (key, snapshot) => Future.successful { entries.put(key, snapshot); () },
      writeNode = (key, id, bytes) => Future.successful { entries.updateWith(key)(_.map(_.updated(id, bytes))); () },
      deleteNode = (key, id) => Future.successful { entries.updateWith(key)(_.map(_ - id)); () },
      removeRepository = key => Future.successful { removals += 1; entries.remove(key); () },
      onRemoveError = _ => ()
    )
  }

  /** A storage whose first read of a state waits until the case lets it go on. */
  private final class HeldRead extends StateStorage[Future, Int] {
    private val underlying = StateStorage[Future, Int]()
    private var reads      = 0
    val release            = Promise[Unit]()
    def exists(device: String, session: String): Future[Boolean] = underlying.exists(device, session)
    def create(device: String, session: String, state: Int): Future[StateManager[Future]] =
      underlying.create(device, session, state)
    def remove(device: String, session: String): Unit = underlying.remove(device, session)
    def get(device: String, session: String): Future[StateManager[Future]] = {
      reads += 1
      if (reads > 1) underlying.get(device, session)
      else release.future.flatMap(_ => underlying.get(device, session))(ExecutionContext.parasitic)
    }
  }

  "A poll of the next opening" should "leave the stored state of the session in place" in {
    val repository = new Repository
    val fixture    = new LongPollingFixture(storage = Some(repository.storage))
    fixture.frames(Some(1))
    val first = fixture.view.get

    announcesClick(fixture.frames(Some(2))) shouldBe true

    // The page goes on, so its state is not removed. A removal that only
    // starts here could still run when the new view has attached.
    fixture.view.get should not be theSameInstanceAs(first)
    repository.removals shouldBe 0
    repository.entries.size shouldBe 1
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

  "A message of the next opening" should "leave the stored state in place, also when no poll waits for the old view" in {
    val repository = new Repository
    val fixture    = new LongPollingFixture(storage = Some(repository.storage))
    fixture.frames(Some(1))

    await(fixture.publish(Some(2), click)).status shouldBe Response.Status.Ok

    // The view of opening 1 has left, and no poll waited for it. The newer
    // opening alone says that the page goes on.
    fixture.destroyed shouldBe 1
    fixture.view shouldBe None
    repository.removals shouldBe 0
    patches(fixture.frames(Some(2))) shouldBe true
    fixture.loads shouldBe 1
  }

  "A view that retires by itself while a poll of the next opening waits for it" should
    "leave the stored state to that poll" in {
    val repository = new Repository
    val held       = Promise[Unit]()
    val fixture    = new LongPollingFixture(destroy = () => held.future, storage = Some(repository.storage))
    fixture.frames(Some(1))
    // The view ends as after its idle timeout: it cancels its own input, and
    // with that its topic is gone. No newer opening can replace that topic.
    await(fixture.messaging.createTopic(fixture.qsid).cancel())
    fixture.worker.drain()
    val second = fixture.subscribe(Some(2))
    fixture.status(second) shouldBe None // it waits for the view of opening 1 to leave

    held.success(())
    fixture.worker.drain()

    // The waiting poll says that the page goes on, so the state stays for it.
    fixture.status(second) shouldBe Some(Response.Status.Ok)
    repository.removals shouldBe 0
    repository.entries.size shouldBe 1
    fixture.loads shouldBe 1
  }

  "A poll that waits for a view which cannot be retired" should "fail, and not wait for ever" in {
    // A socket whose input cannot be cancelled, and a reporter that cannot
    // report it: the retirement of the view ends with an error half way, and
    // the view stays registered.
    val input = Queue[Future, String]()
    val socket = new Stream[Future, String] {
      def pull(): Future[Option[String]] = input.stream.pull()
      def cancel(): Future[Unit]         = Future.failed(new IllegalStateException("not cancelled"))
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
    val fixture = new LongPollingFixture(reporter = unreported)
    // A view that a socket attached. It names no owner, so a poll of the same
    // session has to wait until it has left.
    fixture.sessions.createAppIfNeeded(fixture.qsid, fixture.request(None), socket)
    fixture.worker.drain()
    val poll = fixture.subscribe(Some(1))
    poll.isCompleted shouldBe false

    await(input.close())
    fixture.worker.drain()

    poll.value.map(_.failed.map(_.getMessage).toOption) shouldBe Some(Some("not cancelled"))
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

  "A poll that is replaced while the state of its session is still being loaded" should
    "be answered with Gone, and build no view" in {
    val loaded  = Promise[Int]()
    val fixture = new LongPollingFixture(load = () => loaded.future)
    val first   = fixture.subscribe(Some(1))
    fixture.status(first) shouldBe None // it waits for the state
    // A message of opening 2 takes the place of opening 1 meanwhile.
    await(fixture.publish(Some(2), click)).status shouldBe Response.Status.Ok

    loaded.success(0)
    fixture.worker.drain()

    fixture.status(first) shouldBe Some(Response.Status.Gone)
    fixture.built shouldBe 0
    fixture.view shouldBe None
    // The state that was loaded is the session's. The view of opening 2 goes on from it.
    patches(fixture.frames(Some(2))) shouldBe true
    fixture.loads shouldBe 1
    fixture.built shouldBe 1
  }

  "A poll that is replaced while its view is being built" should "be answered with Gone, and its view leaves by itself" in {
    val storage = new HeldRead
    val fixture = new LongPollingFixture(storage = Some(storage))
    val first   = fixture.subscribe(Some(1))
    fixture.status(first) shouldBe None // its view is being built: the state is still being read
    await(fixture.publish(Some(2), click)).status shouldBe Response.Status.Ok

    storage.release.success(())
    fixture.worker.drain()

    // The view was built for an opening that is over. Its first frames must
    // not answer the poll of that opening.
    fixture.status(first) shouldBe Some(Response.Status.Gone)
    fixture.built shouldBe 1
    fixture.destroyed shouldBe 1
    fixture.view shouldBe None
    patches(fixture.frames(Some(2))) shouldBe true
    fixture.built shouldBe 2
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
}
