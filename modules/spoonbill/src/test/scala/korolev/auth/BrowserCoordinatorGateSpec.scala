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
}
