package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolDisposalSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private final class Resource {
    val closed       = Promise[Unit]()
    val releaseClose = Promise[Unit]()
    var closes       = 0
  }
  private given Close[Future, Resource] with {
    def onClose(value: Resource): Future[Unit] = value.closed.future
    def close(value: Resource): Future[Unit] = {
      value.closes += 1
      value.releaseClose.future.map { _ =>
        value.closed.trySuccess(()); ()
      }(ExecutionContext.parasitic)
    }
  }
  private def pool() = new AsyncResourcePool[Future, Resource](
    "disposal",
    Future.successful(new Resource),
    () => Future.successful(0L),
    1,
    1.minute
  )

  "Pool disposal" should "preserve its claim-versus-cleanup observation order" in {
    val resources = pool()
    val borrowed  = await(resources.borrow())
    await(borrowed.give())
    val early     = resources.onDispose()
    val disposing = resources.dispose()
    early.isCompleted shouldBe false
    disposing.isCompleted shouldBe false
    // Existing contract: a late observer sees the disposal claim immediately.
    await(resources.onDispose())
    await(resources.dispose())
    borrowed.value.closes shouldBe 1
    borrowed.value.releaseClose.success(())
    await(disposing)
    await(early)
  }

  "Borrow discard" should "claim cleanup once without making repeated calls join retirement" in {
    val resources = pool()
    val borrowed  = await(resources.borrow())
    val retiring  = borrowed.discard()
    retiring.isCompleted shouldBe false
    await(borrowed.discard())
    await(borrowed.give())
    borrowed.value.closes shouldBe 1
    val waiting = resources.borrow()
    waiting.isCompleted shouldBe false
    borrowed.value.releaseClose.success(())
    await(retiring)
    val replacement = await(waiting)
    replacement.value should not be theSameInstanceAs(borrowed.value)
    replacement.value.releaseClose.success(())
    await(replacement.discard())
  }
}
