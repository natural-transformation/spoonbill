package spoonbill.effect

import java.util.concurrent.CancellationException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*

class StreamConsumedSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "A stream consumption signal" should "complete only after EOF and replay completion" in {
    val source                 = await(Stream(1).mat[Future]())
    val (consumed, downstream) = source.handleConsumed
    consumed.isCompleted shouldBe false
    await(downstream.pull()) shouldBe Some(1)
    consumed.isCompleted shouldBe false
    await(downstream.pull()) shouldBe None
    await(consumed) shouldBe ()
    await(consumed) shouldBe ()
  }

  it should "fail with an asynchronous upstream error instead of stranding the upload waiter" in {
    val read  = Promise[Option[Int]]()
    val error = new IllegalStateException("upload body failed")
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = read.future
      def cancel(): Future[Unit]      = Future.unit
    }
    val (consumed, downstream) = source.handleConsumed
    val pending                = downstream.pull()
    read.failure(error)
    await(pending.failed) should be theSameInstanceAs error
    consumed.isCompleted shouldBe true
    await(consumed.failed) should be theSameInstanceAs error
  }

  it should "fail when constructing the upstream pull throws" in {
    val error = new IllegalStateException("constructing upload read")
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = throw error
      def cancel(): Future[Unit]      = Future.unit
    }
    val (consumed, downstream) = source.handleConsumed
    await(downstream.pull().failed) should be theSameInstanceAs error
    consumed.isCompleted shouldBe true
    await(consumed.failed) should be theSameInstanceAs error
  }

  it should "settle observers when the consumer explicitly cancels" in {
    var cancellations = 0
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = Future.never
      def cancel(): Future[Unit]      = { cancellations += 1; Future.unit }
    }
    val (consumed, downstream) = source.handleConsumed
    await(downstream.cancel())
    consumed.isCompleted shouldBe true
    await(consumed.failed) shouldBe a[CancellationException]
    cancellations shouldBe 1
  }

  it should "preserve EOF success when cancellation happens afterwards" in {
    val (consumed, downstream) = Stream.empty[Future, Int].handleConsumed
    await(downstream.pull()) shouldBe None
    await(downstream.cancel())
    await(consumed) shouldBe ()
  }
}
