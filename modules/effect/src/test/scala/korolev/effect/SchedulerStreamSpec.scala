package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

class SchedulerStreamSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private val scheduler                     = new Scheduler[Future]
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "A scheduled stream" should "remain canceled when cancellation precedes its first pull" in {
    val stream = await(scheduler.schedule(Duration.Zero))
    await(stream.cancel())
    await(stream.pull()) shouldBe None
  }

  it should "cancel after completed ticks without completing their callbacks again" in {
    val stream = await(scheduler.schedule(Duration.Zero))
    await(stream.pull()) shouldBe Some(())
    await(stream.pull()) shouldBe Some(())
    await(stream.cancel())
    await(stream.cancel())
    await(stream.pull()) shouldBe None
  }

  it should "settle every pending tick once when canceled" in {
    val stream = await(scheduler.schedule(1.day))
    val first  = stream.pull()
    val second = stream.pull()
    await(stream.cancel())
    first.isCompleted shouldBe true
    second.isCompleted shouldBe true
    await(first) shouldBe None
    await(second) shouldBe None
    await(stream.cancel())
    await(stream.pull()) shouldBe None
  }
}
