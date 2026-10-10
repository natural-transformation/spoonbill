package spoonbill.browserauthbaseline

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.*

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
}
