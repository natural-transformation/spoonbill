package spoonbill.recovery.testkit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.recovery.*

/**
 * Laws every SnapshotPayloadStore must satisfy: immutable puts, exact reads,
 * and deletion outcomes that honor the declared cleanup basis. They check the
 * store's own contract against one process; the adapter author still proves
 * the actual datastore profile (durability point, cross-process fencing,
 * restart behavior) separately, as the qualification guide describes.
 */
trait PayloadStoreLaws[F[_]] extends AnyFlatSpec with Matchers {
  protected given effect: Effect[F]
  protected def newStore(): SnapshotPayloadStore[F]
  protected def timeout: FiniteDuration = 20.seconds
  protected def await[A](value: F[A]): A = Await.result(effect.toFuture(value), timeout)

  private def bytes(text: String): SnapshotBytes = SnapshotBytes.copyOf(text.getBytes("UTF-8"))

  "A snapshot payload store" should "read back exactly the bytes a Durable put stored" in {
    val store   = newStore()
    val content = bytes("durable payload")
    val ref     = PayloadRef.fresh(content)
    await(store.put(ref, content)) shouldBe PayloadWrite.Durable
    await(store.get(ref)) shouldBe PayloadRead.Found(content)
    ref.matches(content) shouldBe true
  }

  it should "report Missing for a reference it never stored" in {
    val store = newStore()
    await(store.get(PayloadRef.fresh(bytes("absent")))) shouldBe PayloadRead.Missing
  }

  it should "accept a duplicate put of the same bytes and refuse different bytes under the same reference" in {
    val store    = newStore()
    val content  = bytes("original")
    val ref      = PayloadRef.fresh(content)
    await(store.put(ref, content)) shouldBe PayloadWrite.Durable
    await(store.put(ref, content)) shouldBe PayloadWrite.Durable
    await(store.put(ref, bytes("different"))) should not be PayloadWrite.Durable
    await(store.get(ref)) shouldBe PayloadRead.Found(content)
  }

  it should "settle deletion according to its declared cleanup basis" in {
    val store   = newStore()
    val content = bytes("retired payload")
    val ref     = PayloadRef.fresh(content)
    await(store.put(ref, content)) shouldBe PayloadWrite.Durable
    val outcome = await(store.delete(ref))
    store.cleanupBasis match {
      case CleanupBasis.Unproven =>
        outcome should not be PayloadDelete.FinalDeleted
      case CleanupBasis.DurableRetirementFence | CleanupBasis.VerifiedQuiescence =>
        outcome shouldBe PayloadDelete.FinalDeleted
        await(store.get(ref)) shouldBe PayloadRead.Missing
        // A late write of the retired reference must not recreate a live payload.
        await(store.put(ref, content)) should not be PayloadWrite.Durable
        await(store.get(ref)) shouldBe PayloadRead.Missing
    }
  }

  it should "fence a write that arrives after the delete of a never-written reference when it claims a fence" in {
    val store = newStore()
    if (store.cleanupBasis != CleanupBasis.DurableRetirementFence) cancel("Only a fencing adapter promises this")
    val content = bytes("paused upload")
    val ref     = PayloadRef.fresh(content)
    await(store.delete(ref)) shouldBe PayloadDelete.FinalDeleted
    await(store.put(ref, content)) shouldBe PayloadWrite.Retired
    await(store.get(ref)) shouldBe PayloadRead.Missing
    await(store.delete(ref)) shouldBe PayloadDelete.FinalDeleted
  }

  it should "never answer FinalDeleted without a fence or proven quiescence" in {
    val store = newStore()
    if (store.cleanupBasis != CleanupBasis.Unproven) cancel("Only an unproven adapter is bound by this law")
    val ref = PayloadRef.fresh(bytes("unproven"))
    await(store.delete(ref)) should not be PayloadDelete.FinalDeleted
    await(store.put(ref, bytes("unproven"))) shouldBe PayloadWrite.Durable
    await(store.delete(ref)) should not be PayloadDelete.FinalDeleted
  }
}
