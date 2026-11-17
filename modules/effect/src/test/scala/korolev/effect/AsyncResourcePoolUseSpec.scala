package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolUseSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private final class Resource { val closed = Promise[Unit]() }
  private given Close[Future, Resource] with {
    def onClose(value: Resource): Future[Unit] = value.closed.future
    def close(value: Resource): Future[Unit]   = { value.closed.trySuccess(()); Future.unit }
  }

  for (synchronous <- List(false, true)) {
    "A pool use" should s"return its borrowed resource after failure (synchronous: $synchronous)" in {
      var created  = 0
      val resource = new Resource
      val pool = new AsyncResourcePool[Future, Resource](
        "use-failure", {
          created += 1
          Future.successful(resource)
        },
        () => Future.successful(0L),
        1,
        1.minute
      )
      val failure = new IllegalStateException("use failed")
      val result = pool.use[Int] { _ =>
        if (synchronous) throw failure else Future.failed(failure)
      }
      await(result.failed) should be theSameInstanceAs failure
      val next = pool.borrow()
      next.isCompleted shouldBe true
      val borrowed = await(next)
      borrowed.value should be theSameInstanceAs resource
      created shouldBe 1
      await(borrowed.give())
      await(pool.dispose())
    }
  }
}
