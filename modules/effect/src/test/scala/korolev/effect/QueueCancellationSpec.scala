package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

class QueueCancellationSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "Queue cancellation" should "replay explicit cancellation to listeners registered after it completed" in {
    val queue = Queue[Future, Int](1)
    val early = queue.cancelSignal
    await(queue.stream.cancel())
    await(early)
    val late = queue.cancelSignal
    late.isCompleted shouldBe true
    await(late)
    await(queue.stream.cancel())
    await(queue.cancelSignal)
  }
}
