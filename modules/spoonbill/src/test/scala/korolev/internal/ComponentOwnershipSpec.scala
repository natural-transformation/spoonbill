package spoonbill.internal

import TimerTestSupport.*
import avocet.Id
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.state.StateManager
import spoonbill.state.javaSerialization.*

class ComponentOwnershipSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]               = new Effect.FutureEffect
  private def result[A](value: Future[A]): A = Await.result(value, 2.seconds)

  "Component ownership" should "hold a successor behind an admitted write, notification, and deletion" in {
    val worker       = new Worker
    val write        = Promise[Unit]()
    val notification = Promise[Unit]()
    val deletion     = Promise[Unit]()
    val storage      = StateManager.cached[Future](onWrite = (_, _) => write.future, onDelete = _ => deletion.future)
    val owners       = new ComponentOwnership(storage, new Reports, worker)
    val address      = Id("1_2")
    val old          = owners.bind(address, true)
    val lease        = old.admit().get
    val running      = lease.run(Effect[Future].flatMap(storage.write(address, 1))(_ => notification.future))
    val next         = owners.bind(address, true)
    val opened       = next.awaitOpen
    next.admit() shouldBe None
    old.admit() shouldBe None
    write.success(())
    worker.drain()
    opened.isCompleted shouldBe false
    notification.success(())
    result(running)
    worker.drain()
    opened.isCompleted shouldBe false
    deletion.success(())
    result(opened) shouldBe true
    result(storage.read[Int](address)) shouldBe None
    next.admit().get.release()
  }

  it should "reject a retired initializer's late result without blocking handoff" in {
    val worker  = new Worker
    val storage = StateManager.cached[Future]()
    val owners  = new ComponentOwnership(storage, new Reports, worker)
    val address = Id("1_2")
    val old     = owners.bind(address, true)
    val loaded  = Promise[Int]()
    val late = Effect[Future].flatMap(loaded.future) { value =>
      old.admit().fold(Future.unit)(_.run(storage.write(address, value)))
    }
    val next = owners.bind(address, true)
    worker.drain()
    result(next.awaitOpen) shouldBe true
    result(next.admit().get.run(storage.write(address, 42)))
    loaded.success(7)
    result(late)
    result(storage.read[Int](address)) shouldBe Some(42)
  }

  it should "resolve retired gate waiters and preserve the predecessor chain" in {
    val worker  = new Worker
    val owners  = new ComponentOwnership(StateManager.cached[Future](), new Reports, worker)
    val address = Id("1_2")
    val first   = owners.bind(address, true)
    val lease   = first.admit().get
    val second  = owners.bind(address, true)
    val waiting = second.awaitOpen
    val third   = owners.bind(address, true)
    result(waiting) shouldBe false
    third.isOpen shouldBe false
    lease.release()
    lease.release()
    worker.drain()
    result(third.awaitOpen) shouldBe true
  }

  it should "drain public root turns without deleting retained application state" in {
    val worker  = new Worker
    val storage = StateManager.cached[Future]()
    val owners  = new ComponentOwnership(storage, new Reports, worker)
    val root    = owners.bind(Id.TopLevel, false)
    val lease   = root.admit().get
    root.retire()
    val finished = root.awaitRetired
    worker.drain()
    finished.isCompleted shouldBe false
    result(lease.run(storage.write(Id.TopLevel, 42)))
    worker.drain()
    result(finished)
    result(storage.read[Int](Id.TopLevel)) shouldBe Some(42)
  }

  it should "drain retired children that have already left the current component tree" in {
    val worker  = new Worker
    val owners  = new ComponentOwnership(StateManager.cached[Future](), new Reports, worker)
    val removed = owners.bind(Id("1_2"), true)
    val lease   = removed.admit().get
    removed.retire()
    val drained = owners.closeAndDrain()
    worker.drain()
    drained.isCompleted shouldBe false
    lease.release()
    worker.drain()
    result(drained)
    intercept[spoonbill.server.SessionAccessDenied](owners.bind(Id("1_3"), true))
  }

  it should "finish independent cleanup after a deletion failure and keep the failed address fenced" in {
    val worker        = new Worker
    val failure       = new IllegalStateException("delete failed")
    var deletions     = Set.empty[Id]
    val failedAddress = Id("1_2")
    val storage = StateManager.cached[Future](onDelete = id => {
      deletions += id
      if (id == failedAddress) Future.failed(failure) else Future.unit
    })
    val owners = new ComponentOwnership(storage, new Reports, worker)
    val old    = owners.bind(failedAddress, true)
    val other  = owners.bind(Id("1_3"), true)
    other.retire()
    old.retire()
    val next = owners.bind(failedAddress, true)
    worker.drain()
    result(next.awaitOpen.failed) shouldBe failure
    next.admit() shouldBe None
    val closed = owners.closeAndDrain()
    worker.drain()
    result(closed.failed) shouldBe failure
    deletions shouldBe Set(failedAddress, Id("1_3"))
  }

  it should "discard late reads and never let a retired access adopt the successor's state" in {
    val worker  = new Worker
    val owners  = new ComponentOwnership(StateManager.cached[Future](), new Reports, worker)
    val old     = owners.bind(Id("1_2"), true)
    val read    = Promise[Int]()
    val reading = old.read(read.future)
    val next    = owners.bind(Id("1_2"), true)
    worker.drain()
    next.isOpen shouldBe true
    read.success(42)
    result(reading) shouldBe None
    result(old.read(throw new AssertionError("Retired read was constructed"))) shouldBe None
  }

  it should "invalidate a traversal snapshot when deletion finishes before successor allocation" in {
    val worker   = new Worker
    val storage  = StateManager.cached[Future](Map(Id("1_2") -> 99))
    val owners   = new ComponentOwnership(storage, new Reports, worker)
    val old      = owners.bind(Id("1_2"), true)
    val snapshot = result(owners.snapshot)
    old.snapshotIsCurrent(snapshot) shouldBe true
    old.retire()
    worker.drain()
    val next = owners.bind(Id("1_2"), true)
    next.isOpen shouldBe true
    next.reused shouldBe false
    next.snapshotIsCurrent(snapshot) shouldBe false
    next.snapshotIsCurrent(result(owners.snapshot)) shouldBe true
  }

  it should "drain whole-view shutdown without deleting through a closed guarded store" in {
    val worker = new Worker
    val storage =
      StateManager.cached[Future](onDelete = _ => fail("Whole-view cleanup attempted a guarded node delete"))
    val owners = new ComponentOwnership(storage, new Reports, worker)
    val child  = owners.bind(Id("1_2"), true)
    val lease  = child.admit().get
    val closed = owners.closeAndDrain()
    worker.drain()
    closed.isCompleted shouldBe false
    child.admit() shouldBe None
    lease.release()
    worker.drain()
    result(closed)
  }

  it should "validate each owner's baseline without invalidating unrelated established components" in {
    val worker   = new Worker
    val owners   = new ComponentOwnership(StateManager.cached[Future](), new Reports, worker)
    val removed  = owners.bind(Id("1_2"), true)
    val retained = owners.bind(Id("1_3"), true)
    val snapshot = result(owners.snapshot)
    removed.retire()
    worker.drain()
    retained.snapshotIsCurrent(snapshot) shouldBe true
    retained.establishBaseline()
    retained.snapshotIsCurrent(snapshot) shouldBe false
    retained.snapshotIsCurrent(result(owners.snapshot)) shouldBe true
  }

  it should "reject a snapshot captured while a successor's gate was still closed" in {
    val worker  = new Worker
    val owners  = new ComponentOwnership(StateManager.cached[Future](), new Reports, worker)
    val old     = owners.bind(Id("1_2"), true)
    val lease   = old.admit().get
    val next    = owners.bind(Id("1_2"), true)
    val pending = result(owners.snapshot)
    lease.release()
    worker.drain()
    next.snapshotIsCurrent(pending) shouldBe false
    val opened = result(owners.snapshot)
    next.establishBaseline()
    next.snapshotIsCurrent(opened) shouldBe false
    next.snapshotIsCurrent(result(owners.snapshot)) shouldBe true
  }

  it should "serialize concurrent admission and address replacement without late writes crossing the handoff" in {
    val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    val ec       = scala.concurrent.ExecutionContext.fromExecutorService(executor)
    try {
      (1 to 100).foreach { _ =>
        val storage = StateManager.cached[Future]()
        val owners  = new ComponentOwnership(storage, new Reports, ec)
        val old     = owners.bind(Id("1_2"), true)
        val start   = new java.util.concurrent.CountDownLatch(1)
        val writer = Future {
          start.await()
          old.admit().fold(Future.unit)(_.run(storage.write(Id("1_2"), 10)))
        }(ec).flatMap(identity)(ec)
        val replacement = Future { start.await(); owners.bind(Id("1_2"), true) }(ec)
        start.countDown()
        result(writer)
        val next = result(replacement)
        result(next.awaitOpen) shouldBe true
        result(storage.read[Int](Id("1_2"))) shouldBe None
        result(next.admit().get.run(storage.write(Id("1_2"), 20)))
        old.admit() shouldBe None
        result(storage.read[Int](Id("1_2"))) shouldBe Some(20)
      }
    } finally ec.shutdown()
  }

  it should "establish a stored baseline once without invalidating later same-owner snapshots" in {
    val owners = new ComponentOwnership(StateManager.cached[Future](), new Reports, new Worker)
    val owner  = owners.bind(Id("1_2"), true)
    val before = result(owners.snapshot)
    owner.establishBaseline()
    owner.snapshotIsCurrent(before) shouldBe false
    val established = result(owners.snapshot)
    owner.establishBaseline()
    owner.snapshotIsCurrent(established) shouldBe true
  }
}
