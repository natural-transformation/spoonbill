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

  it should "release capacity waiters after pull failure without changing that failure or draining the buffer" in {
    val queue   = Queue[Future, Int](1)
    val failure = new IllegalStateException("pull failure")
    await(queue.enqueue(1))
    await(queue.fail(failure))
    // The failure itself releases producers that wait in enqueue. A capacity
    // signal asked for afterwards still waits: admission is open, the buffer full.
    val first  = queue.canOffer
    val second = queue.canOffer
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
    await(queue.fail(failure))
    queue.isClosed shouldBe false
    val capacity = queue.canOffer
    capacity.isCompleted shouldBe false
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
    await(queue.dequeueAll()).toVector shouldBe Vector(1)
    capacity.isCompleted shouldBe true
    await(capacity)
    // There is room again, so the failed queue admits this value.
    await(queue.enqueue(2))
    await(queue.dequeueAll()).toVector shouldBe Vector(2)
    await(queue.offer(3)) shouldBe true
    await(queue.close())
  }

  it should "release every blocked producer without accepting its value" in {
    val queue   = Queue[Future, Int](1)
    val failure = new IllegalStateException("pull failure")
    await(queue.enqueue(1))
    val producers = Vector(queue.enqueue(2), queue.enqueue(3), queue.enqueue(4))
    producers.foreach(_.isCompleted shouldBe false)

    await(queue.fail(failure))
    // Assert liveness before any blocking await, stop, close or drain.
    producers.foreach(_.isCompleted shouldBe true)
    producers.foreach(await)
    await(queue.size()) shouldBe 1
    queue.isClosed shouldBe false
    await(queue.stream.pull().failed) should be theSameInstanceAs failure
    await(queue.dequeueAll()).toVector shouldBe Vector(1)
    await(queue.close())
  }

  it should "not let a later producer wait for room that no pull can make" in {
    val queue   = Queue[Future, Int](1)
    val failure = new IllegalStateException("pull failure")
    await(queue.fail(failure))
    await(queue.enqueue(1))
    // The buffer is full now. This producer returns at once; its value is dropped.
    val late = queue.enqueue(2)
    late.isCompleted shouldBe true
    await(late)
    await(queue.size()) shouldBe 1
    await(queue.dequeueAll()).toVector shouldBe Vector(1)
    await(queue.close())
  }

  it should "release a producer when the failure lands between its check and its registration" in {
    val failure                        = new IllegalStateException("pull failure")
    var beforeRegistration: () => Unit = () => ()
    var interleaved                    = false
    // Effect.promise is the step between the producer's check and its
    // registration as a capacity waiter. Run the armed action right there.
    val effect = new ReaderFiberFutureEffect {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Future[A] = {
        val action = beforeRegistration
        beforeRegistration = () => ()
        action()
        super.promise(register)
      }
    }
    val queue = new Queue[Future, Int](1)(using effect)
    await(queue.enqueue(1))
    beforeRegistration = () => { interleaved = true; queue.failUnsafe(failure) }
    val waiting = queue.enqueue(2)
    interleaved shouldBe true
    waiting.isCompleted shouldBe true
    await(waiting)
    await(queue.dequeueAll()).toVector shouldBe Vector(1)
    await(queue.close())
  }
}
