package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*

class SchedulerJobSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private val scheduler                     = new Scheduler[Future]
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "A scheduled result" should "reach every registered waiter and replay to later readers" in {
    val entered    = Promise[Unit]()
    val completion = Promise[Int]()
    val job = scheduler.unsafeScheduleOnce(Duration.Zero) {
      entered.success(())
      completion.future
    }
    await(entered.future)
    val first  = job.result
    val second = job.result
    completion.success(7)
    await(second) shouldBe 7
    first.isCompleted shouldBe true
    await(first) shouldBe 7
    await(job.result) shouldBe 7
  }

  it should "publish asynchronous failures to current and later readers" in {
    val entered    = Promise[Unit]()
    val completion = Promise[Int]()
    val failure    = new IllegalStateException("job failed")
    val job = scheduler.unsafeScheduleOnce(Duration.Zero) {
      entered.success(())
      completion.future
    }
    await(entered.future)
    val first = job.result
    completion.failure(failure)
    await(first.failed) should be theSameInstanceAs failure
    val late = job.result
    late.isCompleted shouldBe true
    await(late.failed) should be theSameInstanceAs failure
  }

  it should "capture a synchronous job factory throw as its result" in {
    val entered = Promise[Unit]()
    val failure = new IllegalStateException("job factory threw")
    val job = scheduler.unsafeScheduleOnce[Int](Duration.Zero) {
      entered.success(())
      throw failure
    }
    await(entered.future)
    await(job.result.failed) should be theSameInstanceAs failure
  }

  it should "leave a canceled unstarted job unresolved without evaluating its factory" in {
    var evaluated = false
    val job       = scheduler.unsafeScheduleOnce(1.day) { evaluated = true; Future.successful(7) }
    val result    = job.result
    job.unsafeCancel()
    await(job.cancel())
    evaluated shouldBe false
    result.isCompleted shouldBe false
    job.result.isCompleted shouldBe false
  }
}
