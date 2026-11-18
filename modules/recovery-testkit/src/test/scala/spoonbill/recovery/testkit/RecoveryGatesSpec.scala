package spoonbill.recovery.testkit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.recovery.{FaultDecision, RecoveryFault}

class RecoveryGatesSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private val point                         = RecoveryFault.BeforePayloadDispatch
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "Recovery gates" should "hold only the first arrival and notify all reached observers" in {
    val gates = new RecoveryGates[Future]
    await(gates.at(point)) shouldBe FaultDecision.Proceed
    val gate           = gates.arm(point)
    val firstObserver  = gate.reached
    val secondObserver = gate.reached
    val first          = gates.at(point)
    await(firstObserver)
    await(secondObserver)
    await(gate.reached)
    first.isCompleted shouldBe false
    await(gates.at(point)) shouldBe FaultDecision.Proceed
    await(gate.resume(FaultDecision.FailBeforeCommit))
    await(first) shouldBe FaultDecision.FailBeforeCommit
    await(gates.at(point)) shouldBe FaultDecision.Proceed
    await(gate.resume(FaultDecision.Proceed))
    await(first) shouldBe FaultDecision.FailBeforeCommit
  }

  it should "disarm an unreached gate when resumed early" in {
    val gates   = new RecoveryGates[Future]
    val gate    = gates.arm(point)
    val reached = gate.reached
    await(gate.resume(FaultDecision.FailBeforeCommit))
    await(gates.at(point)) shouldBe FaultDecision.Proceed
    reached.isCompleted shouldBe false
  }

  it should "keep a replacement armed when an older gate resumes" in {
    val gates       = new RecoveryGates[Future]
    val older       = gates.arm(point)
    val first       = gates.at(point)
    val replacement = gates.arm(point)
    await(older.resume(FaultDecision.FailBeforeCommit))
    await(first) shouldBe FaultDecision.FailBeforeCommit
    val next = gates.at(point)
    next.isCompleted shouldBe false
    await(replacement.reached)
    await(replacement.resume(FaultDecision.LoseReplyAfterCommit))
    await(next) shouldBe FaultDecision.LoseReplyAfterCommit
  }
}
