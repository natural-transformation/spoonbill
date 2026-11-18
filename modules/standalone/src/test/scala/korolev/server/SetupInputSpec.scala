package spoonbill.server

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Stream}

class SetupInputSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private def result[A](value: Future[A]): A = Await.result(value, 5.seconds)

  private class Source extends Stream[Future, Int] {
    var pulls = 0
    var canceled = 0
    def pull(): Future[Option[Int]] = { pulls += 1; Future.successful(Some(pulls)) }
    def cancel(): Future[Unit] = { canceled += 1; Future.unit }
  }

  "Standalone input handoff" should "forward setup waiters in order and make repeated coupling inert" in {
    val source = new Source
    val input = new SetupInput(source)
    val first = input.stream.pull()
    val second = input.stream.pull()
    source.pulls shouldBe 0
    result(input.couple()) shouldBe false
    result(first) shouldBe Some(1)
    result(second) shouldBe Some(2)
    result(input.couple()) shouldBe false
    source.pulls shouldBe 2
    result(input.stream.pull()) shouldBe Some(3)
  }

  it should "settle released setup waiters without closing transport until coupling is attempted" in {
    val source = new Source
    val input = new SetupInput(source)
    val waiting = input.stream.pull()
    result(input.releaseApplication())
    result(waiting) shouldBe None
    result(input.stream.pull()) shouldBe None
    source.canceled shouldBe 0
    result(input.couple()) shouldBe true
    source.canceled shouldBe 1
    source.pulls shouldBe 0
  }

  it should "notify coupled cancellation once, including a failing callback" in {
    val source = new Source
    val input = new SetupInput(source)
    val error = new IllegalStateException("cleanup")
    var ended = 0
    input.onInputEnd(() => { ended += 1; Future.failed(error) })
    result(input.couple()) shouldBe false
    result(input.stream.cancel().failed) shouldBe error
    result(input.stream.cancel())
    result(input.stream.pull()) shouldBe None
    ended shouldBe 1
    source.canceled shouldBe 0
  }

  it should "treat a failing terminal predicate as input termination" in {
    val input = new SetupInput(new Source)
    var ended = 0
    result(input.drain(_ => throw new IllegalStateException("predicate"), () => { ended += 1; Future.unit }))
    ended shouldBe 1
  }

  for (sourceFailure <- List(false, true)) {
    it should s"invoke a failing drain terminal callback only once (sourceFailure=$sourceFailure)" in {
      val error = new IllegalStateException("terminal cleanup")
      val source = new Stream[Future, Int] {
        private var pulls = 0
        def pull(): Future[Option[Int]] = {
          pulls += 1
          if (pulls == 1) Future.successful(Some(1))
          else if (sourceFailure) Future.failed(new IllegalStateException("input")) else Future.successful(None)
        }
        def cancel(): Future[Unit] = Future.unit
      }
      val input = new SetupInput(source)
      var ended = 0
      result(input.drain(_ => false, () => { ended += 1; Future.failed(error) }).failed) shouldBe error
      ended shouldBe 1
    }
  }
}
