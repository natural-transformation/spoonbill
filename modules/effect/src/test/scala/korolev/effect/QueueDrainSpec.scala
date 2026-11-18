package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

class QueueDrainSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "A bulk queue drain" should "release a producer waiting for the capacity it frees" in {
    val queue = Queue[Future, Int](2)
    await(queue.enqueue(1))
    await(queue.enqueue(2))
    val waiting = queue.enqueue(3)
    waiting.isCompleted shouldBe false
    await(queue.dequeueAll()).toVector shouldBe Vector(1, 2)
    waiting.isCompleted shouldBe true
    await(waiting)
    await(queue.stream.pull()) shouldBe Some(3)
    await(queue.close())
  }

  it should "notify every waiting producer when multiple slots become available" in {
    val queue = Queue[Future, Int](2)
    await(queue.enqueue(1))
    await(queue.enqueue(2))
    val first  = queue.enqueue(3)
    val second = queue.enqueue(4)
    await(queue.dequeueAll()).toVector shouldBe Vector(1, 2)
    first.isCompleted shouldBe true
    second.isCompleted shouldBe true
    await(first)
    await(second)
    await(queue.dequeueAll()).toSet shouldBe Set(3, 4)
    await(queue.close())
  }
}
