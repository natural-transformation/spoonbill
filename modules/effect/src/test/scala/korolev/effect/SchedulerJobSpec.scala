package spoonbill.effect

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
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
    // A published result is delivered to waiters separately. Await each one;
    // another callback can still be pending when this thread resumes.
    await(second) shouldBe 7
    await(first) shouldBe 7
    await(job.result) shouldBe 7
  }

  it should "replay a published result while a notification is blocked and then reach every waiter" in {
    val notified              = new CountDownLatch(1)
    val release               = new CountDownLatch(1)
    val holdFirstNotification = new AtomicBoolean(true)
    given Effect[Future] = new ReaderFiberFutureEffect {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Future[A] =
        super.promise { complete =>
          register { result =>
            complete(result)
            if (holdFirstNotification.compareAndSet(true, false)) {
              notified.countDown()
              release.await()
              ()
            }
          }
        }
    }
    val scheduler  = new Scheduler[Future]
    val entered    = Promise[Unit]()
    val completion = Promise[Int]()
    val job = scheduler.unsafeScheduleOnce(Duration.Zero) {
      entered.success(())
      completion.future
    }
    await(entered.future)
    val first      = job.result
    val second     = job.result
    val publishing = new Thread(() => { completion.success(7); () })
    val replayed   = Promise[Int]()
    // Construct the replay in its own thread: a regression which invokes
    // callbacks under the publication monitor must time out, not block this
    // test before Await can enforce its bound.
    val reading = new Thread(() => { replayed.completeWith(job.result); () })
    try {
      publishing.start()
      notified.await(3, TimeUnit.SECONDS) shouldBe true
      // Publication and individual callback delivery are distinct. This
      // observation diagnoses the CI race without requiring callback order.
      info(
        s"All registered waiters complete while one notification is held: ${first.isCompleted && second.isCompleted}"
      )
      reading.start()
      await(replayed.future) shouldBe 7
    } finally {
      release.countDown()
      publishing.join(3000)
      if (reading.getState != Thread.State.NEW) reading.join(3000)
    }
    publishing.isAlive shouldBe false
    reading.isAlive shouldBe false
    await(first) shouldBe 7
    await(second) shouldBe 7
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
