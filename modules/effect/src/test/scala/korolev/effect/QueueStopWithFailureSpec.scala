package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

/**
 * A stop can carry the failure that the stream ends with. Accepted values come
 * first, as after a plain stop.
 */
class QueueStopWithFailureSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private val failure                       = new IllegalStateException("source failure")

  "A queue stopped with a failure" should "give its accepted values in order and then fail every pull" in {
    val queue = Queue[Future, Int](2)
    await(queue.enqueue(1))
    await(queue.enqueue(2))
    await(queue.stopWithFailure(failure))
    queue.isClosed shouldBe false
    await(queue.offer(3)) shouldBe false
    await(queue.stream.pull()) shouldBe Some(1)
    await(queue.stream.pull()) shouldBe Some(2)
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
  }

  it should "fail the pulls that wait" in {
    val queue  = Queue[Future, Int](2)
    val first  = queue.stream.pull()
    val second = queue.stream.pull()
    first.isCompleted shouldBe false
    await(queue.stopWithFailure(failure))
    // Assert liveness before any blocking await.
    first.isCompleted shouldBe true
    second.isCompleted shouldBe true
    await(first.failed) should be theSameInstanceAs failure
    await(second.failed) should be theSameInstanceAs failure
    await(queue.size()) shouldBe 0
  }

  it should "release the producers that wait and take none of their values" in {
    val queue = Queue[Future, Int](1)
    await(queue.enqueue(1))
    val waiting = queue.enqueue(2)
    waiting.isCompleted shouldBe false
    await(queue.stopWithFailure(failure))
    waiting.isCompleted shouldBe true
    await(waiting)
    await(queue.stream.pull()) shouldBe Some(1)
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
  }

  it should "keep the end that the first stop decided" in {
    val ended = Queue[Future, Int](1)
    await(ended.stop())
    await(ended.stopWithFailure(failure))
    await(ended.stream.pull()) shouldBe None

    val failed = Queue[Future, Int](1)
    await(failed.stopWithFailure(failure))
    await(failed.stop())
    await(failed.stopWithFailure(new IllegalStateException("a later stop")))
    await(failed.stream.pull().failed) should be theSameInstanceAs failure
  }

  it should "end without the failure after close and after cancel" in {
    val closed = Queue[Future, Int](1)
    await(closed.enqueue(1))
    await(closed.stopWithFailure(failure))
    await(closed.close())
    await(closed.stream.pull()) shouldBe None

    val canceled = Queue[Future, Int](1)
    await(canceled.stopWithFailure(failure))
    await(canceled.stream.pull().failed) should be theSameInstanceAs failure
    await(canceled.stream.cancel())
    await(canceled.stream.pull()) shouldBe None
  }

  it should "give way to a failure that fails every pull at once" in {
    val queue = Queue[Future, Int](1)
    val now   = new IllegalStateException("pull failure")
    await(queue.enqueue(1))
    await(queue.stopWithFailure(failure))
    await(queue.fail(now))
    await(queue.stream.pull().failed) should be theSameInstanceAs now
  }

  it should "still fail its pulls after a bulk drain took the accepted values" in {
    val queue = Queue[Future, Int](2)
    await(queue.enqueue(1))
    await(queue.enqueue(2))
    await(queue.stopWithFailure(failure))
    await(queue.dequeueAll()).toVector shouldBe Vector(1, 2)
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
  }
}
