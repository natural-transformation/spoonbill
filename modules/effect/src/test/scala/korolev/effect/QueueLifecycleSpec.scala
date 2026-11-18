package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

/**
 * Characterizes distinct admission, pull-failure, and cancellation protocols.
 */
class QueueLifecycleSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "Queue lifecycle" should "broadcast a direct handoff to all currently waiting pulls" in {
    val queue  = Queue[Future, Int](1)
    val first  = queue.stream.pull()
    val second = queue.stream.pull()
    await(queue.offer(7)) shouldBe true
    await(first) shouldBe Some(7)
    await(second) shouldBe Some(7)
    await(queue.size()) shouldBe 0
    await(queue.close())
  }

  it should "keep stopping distinct from closing and retain direct drain access after close" in {
    val queue = Queue[Future, Int](2)
    await(queue.offer(1)) shouldBe true
    await(queue.offer(2)) shouldBe true
    await(queue.stop())
    queue.isClosed shouldBe false
    await(queue.offer(3)) shouldBe false
    await(queue.close())
    queue.isClosed shouldBe true
    await(queue.stream.pull()) shouldBe None
    await(queue.dequeueAll()).toVector shouldBe Vector(1, 2)
  }

  it should "keep pull failure separate from admission until stop or close" in {
    val queue   = Queue[Future, Int](2)
    val failure = new IllegalStateException("pull failure")
    await(queue.fail(failure))
    queue.isClosed shouldBe false
    await(queue.offer(1)) shouldBe true
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
    await(queue.stop())
    await(queue.offer(2)) shouldBe false
    await(queue.close())
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
  }

  it should "signal explicit stream cancellation separately from close" in {
    val queue  = Queue[Future, Int](1)
    val before = queue.cancelSignal
    await(queue.close())
    before.isCompleted shouldBe false
    await(queue.stream.cancel())
    await(before)
  }
}
