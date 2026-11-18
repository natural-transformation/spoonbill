package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

class QueueStopSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "Graceful queue stop" should "release every blocked producer before a capacity-one queue drains to EOF" in {
    val queue = Queue[Future, Int](1)
    await(queue.enqueue(1))
    val producers = Vector(queue.enqueue(2), queue.enqueue(3), queue.enqueue(4))
    producers.foreach(_.isCompleted shouldBe false)

    await(queue.stop())
    // Assert liveness before any blocking await or consumer creates capacity.
    producers.foreach(_.isCompleted shouldBe true)
    producers.foreach(await)
    await(queue.size()) shouldBe 1
    queue.isClosed shouldBe false
    await(queue.offer(5)) shouldBe false
    await(queue.stream.pull()) shouldBe Some(1)
    await(queue.stream.pull()) shouldBe None
    await(queue.size()) shouldBe 0
    await(queue.close())
  }

  it should "detach all capacity callbacks before notifying them once under reentrant stop" in {
    val queue = Queue[Future, Int](1)
    await(queue.enqueue(1))
    val first        = queue.canOffer
    val second       = queue.canOffer
    val observations = ArrayBuffer.empty[(Int, Int, Boolean)]
    first.foreach { _ =>
      observations += ((1, await(queue.size()), queue.offerUnsafe(9))); ()
    }(ExecutionContext.parasitic)
    second.foreach { _ =>
      queue.unsafeStop()
      observations += ((2, await(queue.size()), queue.offerUnsafe(9)))
      ()
    }(ExecutionContext.parasitic)

    queue.unsafeStop()
    first.isCompleted shouldBe true
    second.isCompleted shouldBe true
    observations.toVector shouldBe Vector((2, 1, false), (1, 1, false))
    queue.unsafeStop()
    await(queue.stream.pull()) shouldBe Some(1)
    await(queue.stream.pull()) shouldBe None
    await(queue.close())
    observations.size shouldBe 2
  }

  it should "release blocked producers after pull failure without changing that failure or draining the buffer" in {
    val queue   = Queue[Future, Int](1)
    val failure = new IllegalStateException("pull failure")
    await(queue.enqueue(1))
    val first  = queue.enqueue(2)
    val second = queue.enqueue(3)
    await(queue.fail(failure))
    first.isCompleted shouldBe false
    second.isCompleted shouldBe false
    await(queue.stop())
    first.isCompleted shouldBe true
    second.isCompleted shouldBe true
    await(first)
    await(second)
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
    await(queue.dequeueAll()).toVector shouldBe Vector(1)
    await(queue.close())
  }

  "A failed queue" should "keep admission and bulk-drain capacity recovery separate from pull failure" in {
    val queue   = Queue[Future, Int](1)
    val failure = new IllegalStateException("pull failure")
    await(queue.enqueue(1))
    val waiting = queue.enqueue(2)
    await(queue.fail(failure))
    waiting.isCompleted shouldBe false
    queue.isClosed shouldBe false
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
    await(queue.dequeueAll()).toVector shouldBe Vector(1)
    waiting.isCompleted shouldBe true
    await(waiting)
    await(queue.dequeueAll()).toVector shouldBe Vector(2)
    await(queue.offer(3)) shouldBe true
    await(queue.close())
  }
}
