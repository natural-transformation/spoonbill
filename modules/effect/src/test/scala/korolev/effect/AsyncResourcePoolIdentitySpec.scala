package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolIdentitySpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private final class Resource {
    val closed                               = Promise[Unit]()
    override def equals(other: Any): Boolean = other.isInstanceOf[Resource]
    override def hashCode(): Int             = 1
  }
  private given Close[Future, Resource] with {
    def onClose(value: Resource): Future[Unit] = value.closed.future
    def close(value: Resource): Future[Unit]   = { value.closed.trySuccess(()); Future.unit }
  }

  "Pool resource identity" should "retain a different idle resource even when its value compares equal" in {
    val pool = new AsyncResourcePool[Future, Resource](
      "equal-values",
      Future.successful(new Resource),
      () => Future.successful(0L),
      2,
      1.minute
    )
    val first  = await(pool.borrow())
    val second = await(pool.borrow())
    await(first.give())
    await(second.give())
    first.value.closed.success(())
    val remaining = await(pool.borrow())
    remaining.value should be theSameInstanceAs second.value
    pool.unsafeGetState.total shouldBe 1
    await(remaining.discard())
  }

  it should "retire every allocated lease when factory results share the same object identity" in {
    val resource = new Resource
    val pool = new AsyncResourcePool[Future, Resource](
      "aliased-values",
      Future.successful(resource),
      () => Future.successful(0L),
      2,
      1.minute
    )
    val first  = await(pool.borrow())
    val second = await(pool.borrow())
    pool.unsafeGetState.total shouldBe 2
    resource.closed.success(())
    pool.unsafeGetState.total shouldBe 0
    await(first.give())
    await(second.give())
    pool.unsafeGetState.items shouldBe empty
    pool.unsafeGetState.closedItems shouldBe empty
  }
}
