package spoonbill.effect

import java.util.concurrent.CancellationException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*

class HubTerminalSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private final class Input extends Stream[Future, Int] {
    val read                        = Promise[Option[Int]]()
    val cancellation                = new CancellationException("upstream canceled")
    var pulls                       = 0
    var cancellations               = 0
    def pull(): Future[Option[Int]] = { pulls += 1; read.future }
    def cancel(): Future[Unit]      = { cancellations += 1; read.tryFailure(cancellation); Future.unit }
  }

  for (cancelUpstream <- List(false, true)) {
    "A terminal upstream failure" should s"settle every hub subscriber (cancel-induced: $cancelUpstream)" in {
      val input   = new Input
      val hub     = Hub(input, 2)
      val first   = hub.newStreamUnsafe()
      val second  = hub.newStreamUnsafe()
      val driving = first.pull()
      val waiting = second.pull()
      val failure = if (cancelUpstream) input.cancellation else new IllegalStateException("upstream failed")
      try {
        if (cancelUpstream) await(input.cancel()) else input.read.failure(failure)
        await(driving.failed) should be theSameInstanceAs failure
        waiting.isCompleted shouldBe true
        await(waiting.failed) should be theSameInstanceAs failure
        await(first.pull().failed) should be theSameInstanceAs failure
        input.pulls shouldBe 1
        input.cancellations shouldBe (if (cancelUpstream) 1 else 0)
        intercept[IllegalStateException](hub.newStreamUnsafe())
      } finally { await(first.cancel()); await(second.cancel()) }
    }
  }

  "A completed hub" should "end later pulls without evaluating upstream again" in {
    val input      = new Input
    val hub        = Hub(input, 2)
    val subscriber = hub.newStreamUnsafe()
    val pending    = subscriber.pull()
    input.read.success(None)
    await(pending) shouldBe None
    await(subscriber.pull()) shouldBe None
    input.pulls shouldBe 1
    intercept[IllegalStateException](hub.newStreamUnsafe())
  }

  "A synchronously thrown upstream failure" should "close the hub with the original error" in {
    val error = new IllegalStateException("constructing pull")
    var pulls = 0
    val input = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = { pulls += 1; throw error }
      def cancel(): Future[Unit]      = Future.unit
    }
    val hub    = Hub(input)
    val first  = hub.newStreamUnsafe()
    val second = hub.newStreamUnsafe()
    await(first.pull().failed) should be theSameInstanceAs error
    await(second.pull().failed) should be theSameInstanceAs error
    pulls shouldBe 1
  }
}
