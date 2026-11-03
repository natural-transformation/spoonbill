package spoonbill.auth

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*

class BrowserCoordinatorGateSpec extends AnyFlatSpec with Matchers {
  private def await[A](value: Future[A]): A = Await.result(value, 2.seconds)

  "The browser coordinator gate" should "reserve cleanup when ordinary admission is full and drain both before close" in {
    val gate                      = new BrowserCoordinatorGate[Future](1)
    val held                      = Promise[Int]()
    val order                     = new AtomicReference(Vector.empty[String])
    def mark(value: String): Unit = { order.updateAndGet(_ :+ value); () }
    val running                   = gate(held.future)
    val ordinary                  = gate { mark("ordinary"); Future.successful(2) }
    val overflowRan               = new AtomicBoolean()
    val overflow                  = gate { overflowRan.set(true); Future.successful(3) }
    await(overflow.failed)
    overflowRan.get() shouldBe false
    val cleanup        = gate.cleanup { mark("cleanup"); Future.unit }
    val closing        = gate.close { mark("close"); Future.unit }
    val lateCleanupRan = new AtomicBoolean()
    val lateCleanup    = gate.cleanup { lateCleanupRan.set(true); Future.unit }
    closing.isCompleted shouldBe false
    cleanup.isCompleted shouldBe false
    lateCleanup.isCompleted shouldBe false
    held.success(1)
    await(running) shouldBe 1
    await(ordinary) shouldBe 2
    await(cleanup)
    await(closing)
    await(lateCleanup)
    order.get().toSet shouldBe Set("ordinary", "cleanup", "close")
    order.get().size shouldBe 3
    order.get().last shouldBe "close"
    lateCleanupRan.get() shouldBe false
    await(gate(Future.successful(4)).failed)
  }

  it should "advance after synchronous construction failure" in {
    val gate    = new BrowserCoordinatorGate[Future](1)
    val failure = new IllegalStateException("Synchronous operation failure")
    val failed  = gate[Int](throw failure)
    await(failed.failed) shouldBe failure
    await(gate(Future.successful(42))) shouldBe 42
    await(gate.close(Future.unit))
  }

  it should "advance after asynchronous failure and preserve cleanup failure without stranding close" in {
    val gate           = new BrowserCoordinatorGate[Future](1)
    val held           = Promise[Int]()
    val failure        = new IllegalStateException("Asynchronous operation failure")
    val cleanupFailure = new IllegalStateException("Cleanup failure")
    val running        = gate(held.future)
    val queued         = gate(Future.successful(42))
    val cleanup        = gate.cleanup(Future.failed(cleanupFailure))
    val closed         = new AtomicBoolean()
    val closing        = gate.close { closed.set(true); Future.unit }
    held.failure(failure)
    await(running.failed) shouldBe failure
    await(queued) shouldBe 42
    await(cleanup.failed) shouldBe cleanupFailure
    await(closing)
    closed.get() shouldBe true
  }

  it should "execute close once and share its settled failure with aliases and late cleanup" in {
    val gate    = new BrowserCoordinatorGate[Future](1)
    val held    = Promise[Unit]()
    val count   = new AtomicInteger()
    val running = gate(held.future)
    val failure = new IllegalStateException("Shutdown failure")
    val first   = gate.close { count.incrementAndGet(); Future.failed(failure) }
    val second  = gate.close { count.incrementAndGet(); Future.unit }
    held.success(())
    await(running)
    await(first.failed) shouldBe failure
    await(second.failed) shouldBe failure
    await(gate.close { count.incrementAndGet(); Future.unit }.failed) shouldBe failure
    await(gate.cleanup { count.incrementAndGet(); Future.unit }.failed) shouldBe failure
    count.get() shouldBe 1
  }

  "Read turns" should "run concurrently with each other and never with a write turn" in {
    val gate                      = new BrowserCoordinatorGate[Future](capacity = 4, readCapacity = 3)
    val order                     = new AtomicReference(Vector.empty[String])
    def mark(value: String): Unit = { order.updateAndGet(_ :+ value); () }
    val heldA                     = Promise[Int]()
    val heldB                     = Promise[Int]()
    val readA                     = gate.read("a") { mark("a"); heldA.future }
    val readB                     = gate.read("b") { mark("b"); heldB.future }
    order.get() shouldBe Vector("a", "b")
    gate.stats.runningReads shouldBe 2
    val write = gate { mark("write"); Future.successful(3) }
    // The queued write turn blocks a later read even though a read slot is free.
    val readC = gate.read("c") { mark("c"); Future.successful(4) }
    order.get() shouldBe Vector("a", "b")
    gate.stats.queuedWrites shouldBe 1
    gate.stats.queuedReads shouldBe 1
    heldA.success(1)
    order.get() shouldBe Vector("a", "b")
    heldB.success(2)
    await(readA) shouldBe 1
    await(readB) shouldBe 2
    await(write) shouldBe 3
    await(readC) shouldBe 4
    order.get() shouldBe Vector("a", "b", "write", "c")
    await(gate.close(Future.unit))
  }

  it should "queue reads past the cap and reject only at the queue bound" in {
    val gate   = new BrowserCoordinatorGate[Future](capacity = 1, readCapacity = 1)
    val held   = Promise[Int]()
    val first  = gate.read("a")(held.future)
    val ran    = new AtomicBoolean()
    val second = gate.read("b") { ran.set(true); Future.successful(2) }
    ran.get() shouldBe false
    second.isCompleted shouldBe false
    await(gate.read("c")(Future.successful(3)).failed)
    gate.stats.rejections shouldBe 1L
    held.success(1)
    await(first) shouldBe 1
    await(second) shouldBe 2
    await(gate.close(Future.unit))
  }

  it should "grant readers queued behind a writer up to the cap and keep the rest queued" in {
    val gate                      = new BrowserCoordinatorGate[Future](capacity = 8, readCapacity = 2)
    val order                     = new AtomicReference(Vector.empty[String])
    def mark(value: String): Unit = { order.updateAndGet(_ :+ value); () }
    val heldWrite                 = Promise[Unit]()
    val write                     = gate { mark("write"); heldWrite.future }
    val heldA                     = Promise[Unit]()
    val heldB                     = Promise[Unit]()
    val readA                     = gate.read("a") { mark("a"); heldA.future }
    val readB                     = gate.read("b") { mark("b"); heldB.future }
    val readC                     = gate.read("c") { mark("c"); Future.unit }
    order.get() shouldBe Vector("write")
    heldWrite.success(())
    await(write)
    order.get() shouldBe Vector("write", "a", "b")
    gate.stats.runningReads shouldBe 2
    gate.stats.queuedReads shouldBe 1
    heldA.success(())
    await(readA)
    await(readC)
    order.get() shouldBe Vector("write", "a", "b", "c")
    heldB.success(())
    await(readB)
    await(gate.close(Future.unit))
  }

  it should "run one read per key, let other keys pass a waiting one, and count the wait" in {
    val gate                      = new BrowserCoordinatorGate[Future](capacity = 8, readCapacity = 2)
    val order                     = new AtomicReference(Vector.empty[String])
    def mark(value: String): Unit = { order.updateAndGet(_ :+ value); () }
    val held                      = Promise[Unit]()
    val first                     = gate.read("k") { mark("k1"); held.future }
    val second                    = gate.read("k") { mark("k2"); Future.unit }
    val other                     = gate.read("m") { mark("m"); Future.unit }
    // The second read of the key waits in place without a slot; the other key passes it.
    order.get() shouldBe Vector("k1", "m")
    gate.stats.runningReads shouldBe 1
    gate.stats.queuedReads shouldBe 1
    held.success(())
    await(first)
    await(second)
    await(other)
    order.get() shouldBe Vector("k1", "m", "k2")
    gate.stats.bindingWaits shouldBe 1L
    await(gate.close(Future.unit))
  }

  it should "let close wait for running reads and reject reads requested after it" in {
    val gate    = new BrowserCoordinatorGate[Future](capacity = 4, readCapacity = 2)
    val held    = Promise[Unit]()
    val read    = gate.read("a")(held.future)
    val closed  = new AtomicBoolean()
    val closing = gate.close { closed.set(true); Future.unit }
    gate.isClosing shouldBe true
    closed.get() shouldBe false
    await(gate.read("b")(Future.unit).failed)
    held.success(())
    await(read)
    await(closing)
    closed.get() shouldBe true
    await(gate.read("c")(Future.unit).failed)
  }
}
