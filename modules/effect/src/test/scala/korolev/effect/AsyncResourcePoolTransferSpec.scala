package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolTransferSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private final class Resource { val closed = Promise[Unit]() }
  private given Close[Future, Resource] with {
    def onClose(value: Resource): Future[Unit] = value.closed.future
    def close(value: Resource): Future[Unit]   = { value.closed.trySuccess(()); Future.unit }
  }
  private def pool() = new AsyncResourcePool[Future, Resource](
    "transfer",
    Future.successful(new Resource),
    () => Future.successful(0L),
    1,
    1.minute
  )

  "A transferred pool loan" should "remain owned by the successful result until that owner returns it" in {
    val resources   = pool()
    val transferred = await(resources.borrowAndTransfer(value => Future.successful(value))(_.discard()))
    val next        = resources.borrow()
    next.isCompleted shouldBe false
    await(transferred.give())
    val borrowed = await(next)
    borrowed.value should be theSameInstanceAs transferred.value
    await(borrowed.discard())
  }

  it should "abort on strict task construction failure without replacing its error" in {
    val resources = pool()
    val failure   = new IllegalStateException("strict task failed")
    var aborted   = 0
    val result = resources.borrowAndTransfer[Int](_ => throw failure) { value =>
      aborted += 1
      value.discard()
    }
    await(result.failed) should be theSameInstanceAs failure
    aborted shouldBe 1
    resources.unsafeGetState.total shouldBe 0
  }

  it should "not close a resource already returned by a failing task" in {
    val resources = pool()
    val failure   = new IllegalStateException("failure after return")
    var returned  = Option.empty[Resource]
    val result = resources.borrowAndTransfer[Int] { value =>
      returned = Some(value.value)
      await(value.give())
      Future.failed(failure)
    }(_.discard())
    await(result.failed) should be theSameInstanceAs failure
    val next = await(resources.borrow())
    next.value should be theSameInstanceAs returned.get
    next.value.closed.isCompleted shouldBe false
    await(next.discard())
  }
}
