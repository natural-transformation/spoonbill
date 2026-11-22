package spoonbill.server.internal.services

import avocet.Id
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Queue}
import spoonbill.state.{StateManager, StateStorage}
import spoonbill.state.javaSerialization.*

/**
 * The state of an unguarded page that nothing has claimed. A storage that is
 * local to this process loses it at a render after its lifetime; from any
 * other storage this change deletes nothing.
 */
class UnclaimedPageSpec extends AnyFlatSpec with Matchers {
  import LongPollingFixture.*
  private given Effect[Future] = Effect.futureEffect

  private val reload = "[1]"

  /** A repository in memory, under `StateStorage.fromRepository`, whose deletions can be held. */
  private final class Repository(holdDeletions: Boolean) {
    val rows    = TrieMap.empty[String, TrieMap[Id, Array[Byte]]]
    val removes = new AtomicInteger()
    val held    = Promise[Unit]()
    /** A storage over this repository, as each server has one. */
    def storage(): StateStorage[Future, Int] = StateStorage.fromRepository[Future, Int](
      repositoryExists = key => Future.successful(rows.contains(key)),
      loadSnapshot = key => Future.successful(rows.getOrElse(key, TrieMap.empty).toMap),
      createSnapshot = (key, snapshot) => Future.successful { rows.put(key, TrieMap.from(snapshot)); () },
      writeNode = (key, node, bytes) => Future.successful { rows.getOrElseUpdate(key, TrieMap.empty).put(node, bytes); () },
      deleteNode = (key, node) => Future.successful { rows.get(key).foreach(_.remove(node)); () },
      removeRepository = key => {
        removes.incrementAndGet()
        if (holdDeletions) held.future.map(_ => { rows.remove(key); () })(ExecutionContext.parasitic)
        else Future.successful { rows.remove(key); () }
      }
    )
    def key(page: LongPollingFixture#Page): String = s"${page.qsid.deviceId}-${page.qsid.sessionId}"
  }

  /** A storage local to this process, over the default one, that can hold or fail its removal. */
  private final class Local(capacity: Int = 5000) extends StateStorage[Future, Int] {
    @volatile var failReads = false
    // Development mode is one switch for the whole JVM, and a suite beside this
    // one may turn it on: its states go to files that a removal leaves. This
    // storage stays in memory whatever that switch says.
    private val underlying = new StateStorage.DefaultStateStorage[Future, Int](capacity, allowDevMode = false)
    val reads              = new AtomicInteger()
    val removed            = TrieMap.empty[String, Int]
    @volatile var failNext = false
    @volatile var hold: Option[(CountDownLatch, CountDownLatch)] = None // (entered, release)
    override private[spoonbill] def localToProcess: Boolean = true
    def exists(device: String, session: String): Future[Boolean] = { reads.incrementAndGet(); underlying.exists(device, session) }
    def create(device: String, session: String, state: Int): Future[StateManager[Future]] = underlying.create(device, session, state)
    def get(device: String, session: String): Future[StateManager[Future]] = {
      reads.incrementAndGet()
      if (failReads) Future.failed(new IllegalStateException("the state cannot be read")) else underlying.get(device, session)
    }
    def remove(device: String, session: String): Unit = {
      hold.foreach { case (entered, release) => entered.countDown(); release.await(10, TimeUnit.SECONDS) }
      if (failNext) { failNext = false; throw new IllegalStateException("the removal failed") }
      removed.updateWith(s"$device-$session")(count => Some(count.getOrElse(0) + 1))
      underlying.remove(device, session)
    }
    def live(device: String, session: String): Boolean = Await(underlying.exists(device, session))
  }
  private def Await[A](value: Future[A]): A = scala.concurrent.Await.result(value, 5.seconds)

  "A storage that is not local" should "never be asked to delete: not at an expiry, a reload or a failed attach" in {
    val repository = new Repository(holdDeletions = true)
    val f          = new LongPollingFixture(idleTimeout = 10.seconds, storage = Some(repository.storage()))
    val pages      = Vector.fill(3)(f.render())
    f.advance(11.seconds)
    // The next render ends the expired records.
    f.render()
    f.sessions.freshPageRecords shouldBe 1
    // An expired page polls and is told to reload.
    read(await(f.poll(pages.head, 0L))).body shouldBe reload
    // On another server, an attach of a page whose row holds no state fails
    // before it has a view.
    val other = new LongPollingFixture(storage = Some(repository.storage()))
    repository.rows.put(repository.key(pages(1)), TrieMap.empty)
    other.webSocket(other.Page(pages(1).qsid, pages(1).id)).value.exists(_.isFailure) shouldBe true
    repository.removes.get() shouldBe 0
    repository.rows.keySet should contain allElementsOf pages.map(repository.key)
  }

  "Two servers with one repository" should "leave the row of a session whose view is on the other server" in {
    val repository = new Repository(holdDeletions = false)
    val first      = new LongPollingFixture(storage = Some(repository.storage()))
    val second     = new LongPollingFixture(storage = Some(repository.storage()))
    val page       = first.render()
    val poll       = first.poll(page, 0L)
    first.hasView(page) shouldBe true
    // A poll of the page reaches the server that has no view of it.
    read(await(second.poll(second.Page(page.qsid, page.id), 0L))).body shouldBe reload
    // The row is intact in the repository itself, and the view on the first server goes on.
    repository.rows.contains(repository.key(page)) shouldBe true
    repository.removes.get() shouldBe 0
    first.hasView(page) shouldBe true
    read(await(poll)).frame shouldBe Some(1L)
  }

  "A storage that is local" should "lose the states of pages that expired, and keep the state of a view" in {
    val storage = new Local(capacity = 2)
    val f       = new LongPollingFixture(idleTimeout = 10.seconds, storage = Some(storage))
    val expired = Vector.fill(5)(f.render())
    val viewed  = f.render()
    // The viewed page applies its first frames and keeps a poll waiting, so
    // that its view lives on while the clock passes the lifetime.
    var seen = 0L
    var poll = f.poll(viewed, seen)
    def keepPolling(): Unit =
      while (poll.isCompleted) {
        read(await(poll)).frame.foreach(frame => seen = frame)
        poll = f.poll(viewed, seen)
      }
    keepPolling()
    for (_ <- 1 to 11) { f.advance(1.second); keepPolling() }
    f.hasView(viewed) shouldBe true
    f.render()
    // The for-deletion cache of the default storage holds two: the three oldest are gone.
    expired.map(page => storage.live(page.qsid.deviceId, page.qsid.sessionId)) shouldBe Vector(false, false, false, true, true)
    storage.live(viewed.qsid.deviceId, viewed.qsid.sessionId) shouldBe true
    storage.removed.keySet should not contain s"${viewed.qsid.deviceId}-${viewed.qsid.sessionId}"
  }

  it should "refuse a claim while a removal runs, without reading the storage" in {
    val storage = new Local()
    val f       = new LongPollingFixture(idleTimeout = 10.seconds, storage = Some(storage))
    val page    = f.render()
    f.advance(11.seconds)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    storage.hold = Some(entered -> release)
    // A render ends the record and removes its state. The removal is held.
    val rendering = Future(f.render())(ExecutionContext.global)
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    storage.hold = None
    val reads = storage.reads.get()
    read(await(f.poll(page, 0L))).body shouldBe reload
    storage.reads.get() shouldBe reads
    release.countDown()
    await(rendering)
  }

  it should "report a removal that fails, keep its record, and remove the state at the next render" in {
    val storage = new Local()
    val f       = new LongPollingFixture(idleTimeout = 10.seconds, storage = Some(storage))
    val page    = f.render()
    f.advance(11.seconds)
    storage.failNext = true
    f.render()
    f.reports.errors.map(_._1).exists(_.contains("Unable to remove the state of an unclaimed page")) shouldBe true
    storage.removed.get(s"${page.qsid.deviceId}-${page.qsid.sessionId}") shouldBe None
    f.render()
    storage.removed.get(s"${page.qsid.deviceId}-${page.qsid.sessionId}") shouldBe Some(1)
  }

  it should "lose the state of a page whose attach failed before it had a view" in {
    val storage = new Local()
    val f       = new LongPollingFixture(storage = Some(storage))
    val page    = f.render()
    storage.failReads = true
    f.webSocket(page).value.exists(_.isFailure) shouldBe true
    storage.removed.get(s"${page.qsid.deviceId}-${page.qsid.sessionId}") shouldBe Some(1)
  }
}
