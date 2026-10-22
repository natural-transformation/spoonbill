package spoonbill.effect

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Reporter.PrintReporter.Implicit

final class AsyncResourcePoolDiscardSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  private final class Resource {
    val notification = Promise[Unit]()
    val closes = new AtomicInteger()
  }
  private given Close[Future, Resource] with {
    def onClose(value: Resource): Future[Unit] = value.notification.future
    // Deliberately delay the close notification until after discard returns.
    def close(value: Resource): Future[Unit] = {
      value.closes.incrementAndGet()
      Future.unit
    }
  }

  "Pool discard" should "never recycle closed resources or retain tombstones when close notifications arrive late" in {
    val pool = new AsyncResourcePool[Future, Resource]("discard-test", Future.successful(new Resource),
      () => Future.successful(0L), 1, 1.minute)
    var current = await(pool.borrow())
    for (_ <- 0 until 100) {
      // The discard hook must survive an ordinary return and subsequent borrow.
      await(current.give())
      val borrowed = await(pool.borrow())
      borrowed.value should be theSameInstanceAs current.value
      val waiting = pool.borrow()
      waiting.isCompleted shouldBe false
      await(borrowed.discard())
      await(borrowed.discard())
      await(borrowed.give())
      current = await(waiting)
      current.value should not be theSameInstanceAs(borrowed.value)
      borrowed.value.closes.get() shouldBe 1
      borrowed.value.notification.success(())
      pool.unsafeGetState.total shouldBe 1
      pool.unsafeGetState.items shouldBe empty
      pool.unsafeGetState.closedItems shouldBe empty
    }
    await(current.discard())
    current.value.notification.success(())
    pool.unsafeGetState.total shouldBe 0
    pool.unsafeGetState.items shouldBe empty
    pool.unsafeGetState.closedItems shouldBe empty
  }
}
