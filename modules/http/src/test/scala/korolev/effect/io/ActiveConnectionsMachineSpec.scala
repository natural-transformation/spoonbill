package spoonbill.effect.io

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class ActiveConnectionsMachineSpec extends AnyFlatSpec with Matchers {
  import ActiveConnectionsMachine.*

  "Active connections" should "require both stopped admission and zero live handlers" in {
    completion(initial) shouldBe None
    val live = started(started(initial))
    val stoppedWithLiveHandlers = stopped(live, None)
    completion(stoppedWithLiveHandlers) shouldBe None
    completion(finished(stoppedWithLiveHandlers, None)) shouldBe None
    completion(finished(finished(stoppedWithLiveHandlers, None), None)) shouldBe Some(Right(()))
    completion(finished(finished(live, None), None)) shouldBe None
    completion(stopped(initial, None)) shouldBe Some(Right(()))
  }

  it should "carry the first observed failure across either shutdown ordering" in {
    val first = new IllegalStateException("first")
    val later = new IllegalStateException("later")
    val live = started(initial)
    completion(stopped(finished(live, Some(first)), Some(later))) shouldBe Some(Left(first))
    completion(finished(stopped(live, Some(first)), Some(later))) shouldBe Some(Left(first))
    initial shouldBe State(0, true, None)
  }
}
