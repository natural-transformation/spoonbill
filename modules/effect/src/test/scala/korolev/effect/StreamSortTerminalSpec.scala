package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*

class StreamSortTerminalSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "Sorted stream termination" should "ignore racks that never registered a pull" in {
    val racks = Stream.empty[Future, Int].sort(3)(_ % 3)
    await(racks(2).pull()) shouldBe None
    await(racks(0).pull()) shouldBe None
    await(racks(1).pull()) shouldBe None
  }

  it should "complete registered siblings when upstream cancellation supplies EOF" in {
    val read          = Promise[Option[Int]]()
    val cancelStarted = Promise[Unit]()
    var canceled      = 0
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = read.future
      def cancel(): Future[Unit]      = { canceled += 1; cancelStarted.trySuccess(()); read.trySuccess(None); Future.unit }
    }
    val racks  = source.sort(3)(_ % 3)
    val first  = racks(0).pull()
    val second = racks(1).pull()
    await(racks(2).cancel())
    await(first) shouldBe None
    await(second) shouldBe None
    await(racks(2).pull()) shouldBe None
    // Cancellation dispatches owned cleanup without joining it; observe its
    // entry explicitly before checking the source-side count.
    await(cancelStarted.future)
    canceled shouldBe 1
  }
}
