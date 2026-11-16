package spoonbill.http

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Stream}

class HttpUpgradeInputSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private def result[A](value: Future[A]): A = Await.result(value, 5.seconds)

  "HTTP upgrade input" should "claim transport once and report its claim only on the first seal" in {
    val transport = Stream.empty[Future, Int]
    val input = new HttpUpgradeInput(Stream.empty[Future, Int], transport)
    result(input.claim()) should be theSameInstanceAs transport
    intercept[IllegalStateException](result(input.claim()))
    input.seal() shouldBe true
    input.seal() shouldBe false
    intercept[IllegalStateException](result(input.claim()))
  }

  it should "make an unused capability unavailable when sealed" in {
    val input = new HttpUpgradeInput(Stream.empty[Future, Int], Stream.empty[Future, Int])
    input.seal() shouldBe false
    input.seal() shouldBe false
    intercept[IllegalStateException](result(input.claim()))
  }

  it should "delegate ordinary reads and cancellation without consuming the upgrade capability" in {
    var canceled = 0
    val ordinary = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = Future.successful(Some(7))
      def cancel(): Future[Unit] = { canceled += 1; Future.unit }
    }
    val transport = Stream.empty[Future, Int]
    val input = new HttpUpgradeInput(ordinary, transport)
    result(input.pull()) shouldBe Some(7)
    result(input.cancel())
    canceled shouldBe 1
    result(input.claim()) should be theSameInstanceAs transport
    input.seal() shouldBe true
  }
}
