package spoonbill.browserauthbaseline

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.server.SessionAccessDenied

class ReferencePolicySpec extends AnyFlatSpec with Matchers {
  private def result[A](future: Future[A]): A = Await.result(future, 5.seconds)

  "The executable reference profile" should "retain complete declared work without deleting replay history" in {
    val policy            = ReferencePolicy.Default
    val work              = 10000 + 5000
    val initialPopulation = policy.accountCount * 2
    policy.retainedEntries should be >= (2 * work + initialPopulation)
    policy.retainedViews should be >= (8 * work + initialPopulation)
    policy.auditRecords should be >= (4 * work + initialPopulation)
    policy.accounts.size shouldBe 1000
    policy.accounts.map(_.subject).distinct.size shouldBe 1000
    policy.accounts.map(_.name).distinct.size shouldBe 1000
    policy.accounts.take(2).map(_.name) shouldBe Vector("alice", "bob")
    policy.accounts.count(_.factor.nonEmpty) shouldBe 500
    policy.proof.salt.length shouldBe 16
    val short          = ReferencePolicy.Proof.Short.hash("password")
    val representative = ReferencePolicy.Proof.Representative.hash("password")
    short.length shouldBe 32
    representative.length shouldBe 32
    short.toVector should not be representative.toVector
    policy.verifyPassword("password", short) shouldBe true
    policy.verifyPassword("wrong", short) shouldBe false
  }

  "Reference callback admission" should "bound pre-dispatch work and drain actual settlement without admitting more callbacks" in {
    val admission  = new ReferenceAdmission(1)
    val held       = Promise[Int]()
    val first      = admission.submit(held.future)
    var dispatched = false
    intercept[SessionAccessDenied](result(admission.submit { dispatched = true; Future.successful(2) }))
    dispatched shouldBe false
    admission.counts shouldBe (1 -> 1)
    val closing = admission.close()
    closing.isCompleted shouldBe false
    intercept[SessionAccessDenied](result(admission.submit(Future.successful(3))))
    held.success(1)
    result(first) shouldBe 1
    result(closing) shouldBe ()
    admission.counts shouldBe (0 -> 1)
  }

  it should "release admission after synchronous dispatch failure and before immediate continuations" in {
    val admission = new ReferenceAdmission(1)
    intercept[IllegalStateException](result(admission.submit(throw new IllegalStateException("dispatch"))))
    val held = Promise[Int]()
    val followed =
      admission.submit(held.future).flatMap(_ => admission.submit(Future.successful(2)))(ExecutionContext.parasitic)
    held.success(1)
    result(followed) shouldBe 2
    admission.counts._1 shouldBe 0
  }

  it should "reserve one coalesced maintenance slot and drain it even when ordinary work is full" in {
    val admission   = new ReferenceAdmission(1)
    val ordinary    = Promise[Unit]()
    val cleanup     = Promise[Unit]()
    val active      = admission.submit(ordinary.future)
    val maintenance = admission.submitMaintenance(cleanup.future)
    (maintenance eq admission.submitMaintenance(Future.unit)) shouldBe true
    admission.counts shouldBe (2 -> 2)
    val closing = admission.close()
    ordinary.success(())
    result(active)
    closing.isCompleted shouldBe false
    cleanup.success(())
    result(maintenance); result(closing)
    admission.counts shouldBe (0 -> 2)
  }
}
