package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

class QueueTerminationSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "A stopped queue" should "complete pending pulls once across repeated stop and close" in {
    val queue  = Queue[Future, Int](2)
    val first  = queue.stream.pull()
    val second = queue.stream.pull()
    await(queue.stop())
    await(first) shouldBe None
    await(second) shouldBe None
    await(queue.stop())
    await(queue.close())
    await(queue.stream.pull()) shouldBe None
  }

  it should "drain accepted values in order and reject later offers" in {
    val queue = Queue[Future, Int](2)
    await(queue.enqueue(1))
    await(queue.enqueue(2))
    await(queue.stop())
    await(queue.offer(3)) shouldBe false
    await(queue.stream.pull()) shouldBe Some(1)
    await(queue.stream.pull()) shouldBe Some(2)
    await(queue.stream.pull()) shouldBe None
    await(queue.close())
  }

  it should "retain failure precedence when subsequently closed" in {
    val queue   = Queue[Future, Int](2)
    val failure = new IllegalStateException("failure")
    await(queue.fail(failure))
    await(queue.close())
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
    queue.isClosed shouldBe true
  }
}
