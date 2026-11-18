package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*

class StreamOperatorContractSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private final class Input(values: List[Int]) extends Stream[Future, Int] {
    private var remaining = values
    var reads             = 0
    val cancelStarted     = Promise[Unit]()
    val cancelFinished    = Promise[Unit]()
    def pull(): Future[Option[Int]] = synchronized {
      reads += 1
      val next = remaining.headOption
      remaining = remaining.drop(1)
      Future.successful(next)
    }
    def cancel(): Future[Unit] = { cancelStarted.trySuccess(()); cancelFinished.future }
  }

  "A bounded lossless Hub" should "wait for slow subscriber credit and retain FIFO values" in {
    val input = new Input(List(1, 2, 3))
    val hub   = Hub(input, 1)
    val fast  = hub.newStreamUnsafe()
    val slow  = hub.newStreamUnsafe()
    await(fast.pull()) shouldBe Some(1)
    val next = fast.pull()
    next.isCompleted shouldBe false
    input.reads shouldBe 1
    await(slow.pull()) shouldBe Some(1)
    await(next) shouldBe Some(2)
    await(slow.pull()) shouldBe Some(2)
    await(slow.cancel())
    await(fast.pull()) shouldBe Some(3)
    input.cancelStarted.isCompleted shouldBe false
    await(fast.cancel())
  }

  it should "drain a slow subscriber's accepted tail before EOF" in {
    val hub  = Hub(new Input(List(1, 2)), 2)
    val fast = hub.newStreamUnsafe()
    val slow = hub.newStreamUnsafe()
    await(fast.pull()) shouldBe Some(1)
    await(fast.pull()) shouldBe Some(2)
    val end = fast.pull()
    await(slow.pull()) shouldBe Some(1)
    await(end) shouldBe None
    await(slow.pull()) shouldBe Some(2)
    await(slow.pull()) shouldBe None
  }

  "FIFO stream partitioning" should "keep reading for a waiting rack without overwriting other racks" in {
    val input = new Input(List(1, 3, 0))
    val racks = input.sort(2)(_ % 2)
    await(racks(0).pull()) shouldBe Some(0)
    await(racks(1).pull()) shouldBe Some(1)
    await(racks(1).pull()) shouldBe Some(3)
    await(racks(0).pull()) shouldBe None
    await(racks(1).pull()) shouldBe None
  }

  it should "fail every rack with payload-free metadata on default-capacity overflow" in {
    val input = new Input(List.fill(129)(1))
    val racks = input.sort(2)(identity)
    val error = await(racks(0).pull().failed)
    error shouldBe StreamSortOverflowException(1, 128)
    await(racks(1).pull().failed) should be theSameInstanceAs error
    input.reads shouldBe 129
    await(input.cancelStarted.future)
    input.cancelFinished.isCompleted shouldBe false
    input.cancelFinished.success(())
  }

  it should "honor a configured capacity and reject nonpositive bounds" in {
    val input = new Input(List(1, 1, 1))
    val racks = input.sort(2, 2)(identity)
    await(racks(0).pull().failed) shouldBe StreamSortOverflowException(1, 2)
    input.cancelFinished.success(())
    intercept[IllegalArgumentException](input.sort(2, 0)(identity))
    intercept[IllegalArgumentException](Hub(input, 0))
  }
}
