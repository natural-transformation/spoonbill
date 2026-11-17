package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolCleanupSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private final class Resource {
    val notification = Promise[Unit]()
    var closes       = 0
  }
  private given Close[Future, Resource] with {
    def onClose(value: Resource): Future[Unit] = value.notification.future
    // Physical close can finish before its independent close notification arrives.
    def close(value: Resource): Future[Unit] = { value.closes += 1; Future.unit }
  }

  for (keepBorrowed <- List(false, true)) {
    "Pool cleanup" should s"ignore a late close notification after retiring an idle resource (other borrowed: $keepBorrowed)" in {
      var now = 0L
      val pool = new AsyncResourcePool[Future, Resource](
        "late-cleanup",
        Future.successful(new Resource),
        () => Future.successful(now),
        2,
        1.second
      )
      val idle  = await(pool.borrow())
      val other = if (keepBorrowed) Some(await(pool.borrow())) else None
      await(idle.give())
      now = 1.second.toNanos
      await(pool.cleanup()) shouldBe 1
      idle.value.closes shouldBe 1
      pool.unsafeGetState.total shouldBe other.size
      idle.value.notification.success(())
      pool.unsafeGetState.total shouldBe other.size
      pool.unsafeGetState.closedItems shouldBe empty
      other.foreach { borrowed =>
        await(borrowed.discard())
        borrowed.value.notification.success(())
      }
      pool.unsafeGetState.total shouldBe 0
    }
  }
}
