package spoonbill.browserauthbaseline

import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Promise}
import scala.concurrent.duration.*
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.SessionAccessDenied

class ReferenceServerLifecycleSpec extends AnyFlatSpec with Matchers {
  "The reference worker finalizer" should "wait for actual settlement and release after the actor dispatcher terminates" in {
    val system  = ActorSystem("reference-finalizer-test")
    val workers = Executors.newFixedThreadPool(1)
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val settled = Promise[Unit]()
    try {
      workers.execute { () =>
        started.countDown()
        if (release.await(15, TimeUnit.SECONDS)) settled.trySuccess(())
        else settled.tryFailure(new IllegalStateException("Synthetic settlement barrier timed out"))
        ()
      }
      started.await(5, TimeUnit.SECONDS) shouldBe true
      val released = JdbcReferenceServer.releaseWorkersAfter(settled.future, workers)
      Await.result(system.terminate(), 10.seconds)
      workers.isShutdown shouldBe false
      released.isCompleted shouldBe false
      release.countDown()
      Await.result(released, 5.seconds) shouldBe Done
      workers.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
    } finally {
      release.countDown()
      workers.shutdownNow()
      Await.result(system.terminate(), 10.seconds)
    }
  }

  it should "release the owned pool when settled cleanup fails without hiding the failure" in {
    val workers = Executors.newFixedThreadPool(1)
    val settled = Promise[Unit]()
    val failure = new IllegalStateException("Synthetic cleanup failure")
    try {
      val released = JdbcReferenceServer.releaseWorkersAfter(settled.future, workers)
      workers.isShutdown shouldBe false
      settled.failure(failure)
      intercept[IllegalStateException](Await.result(released, 5.seconds)) shouldBe failure
      workers.isShutdown shouldBe true
    } finally workers.shutdownNow()
  }

  for (maintenance <- Vector(false, true)) {
    it should s"drain memory ${if (maintenance) "maintenance" else "ordinary work"} after dispatcher termination" in {
      val system             = ActorSystem(s"memory-finalizer-${if (maintenance) "maintenance" else "ordinary"}")
      given ExecutionContext = system.dispatcher
      val workers            = Executors.newFixedThreadPool(1)
      val started            = new CountDownLatch(1)
      val release            = new CountDownLatch(1)
      val backend            = new MemoryReferenceBackend("http://localhost:8080", ExecutionContext.fromExecutor(workers))
      try {
        workers.execute { () =>
          started.countDown()
          require(release.await(15, TimeUnit.SECONDS), "Synthetic worker barrier timed out")
        }
        started.await(5, TimeUnit.SECONDS) shouldBe true
        val pending =
          if (maintenance) backend.retireExpiredMaterial()
          else backend.begin(ConnectionId.fromUuid(new UUID(0L, 1L)))
        backend.callbackCounts._1 shouldBe 1
        val closing = JdbcReferenceServer.releaseWorkersAfter(backend.close(), workers)
        Await.result(system.terminate(), 10.seconds)
        closing.isCompleted shouldBe false
        release.countDown()
        if (maintenance) Await.result(pending, 5.seconds)
        else intercept[SessionAccessDenied](Await.result(pending, 5.seconds))
        Await.result(closing, 5.seconds) shouldBe Done
        backend.callbackCounts._1 shouldBe 0
        workers.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
      } finally {
        release.countDown()
        workers.shutdownNow()
        Await.result(system.terminate(), 10.seconds)
      }
    }
  }
}
