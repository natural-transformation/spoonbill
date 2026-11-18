package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolIdleClosureSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private final class Resource { val closed = Promise[Unit]() }
  private given Close[Future, Resource] with {
    def onClose(value: Resource): Future[Unit] = value.closed.future
    def close(value: Resource): Future[Unit]   = { value.closed.trySuccess(()); Future.unit }
  }

  "An idle resource close" should "leave no retired-loan markers when every loan was already returned" in {
    val pool = new AsyncResourcePool[Future, Resource](
      "idle-close",
      Future.successful(new Resource),
      () => Future.successful(0L),
      1,
      1.minute
    )
    for (_ <- 1 to 100) {
      val borrowed = await(pool.borrow())
      await(borrowed.give())
      borrowed.value.closed.success(())
      pool.unsafeGetState.total shouldBe 0
      pool.unsafeGetState.items shouldBe empty
      pool.unsafeGetState.closedItems shouldBe empty
    }
  }
}
