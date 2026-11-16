package spoonbill.recovery.testkit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.recovery.*

class PayloadStoreStateSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]                   = Effect.futureEffect
  private def await[A](value: Future[A]): A      = Await.result(value, 3.seconds)
  private def bytes(text: String): SnapshotBytes = SnapshotBytes.copyOf(text.getBytes("UTF-8"))

  "The reference payload store" should "keep unavailable operations from changing stored or retired data" in {
    val store = new InMemoryPayloadStore[Future]
    val value = bytes("original")
    val ref   = PayloadRef.fresh(value)
    store.failWrites.set(true)
    await(store.put(ref, value)) shouldBe PayloadWrite.Unavailable
    store.contains(ref) shouldBe false
    store.failWrites.set(false)
    await(store.put(ref, value)) shouldBe PayloadWrite.Durable
    store.failDeletes.set(true)
    await(store.delete(ref)) shouldBe PayloadDelete.Unavailable
    await(store.get(ref)) shouldBe PayloadRead.Found(value)
    store.failReads.set(true)
    await(store.get(ref)) shouldBe PayloadRead.Unavailable
    store.failReads.set(false)
    store.failDeletes.set(false)
    await(store.delete(ref)) shouldBe PayloadDelete.FinalDeleted
    store.objectCount shouldBe 0
    await(store.put(ref, value)) shouldBe PayloadWrite.Retired
  }

  it should "preserve a retirement fence when diagnostic corruption installs bytes" in {
    val store    = new InMemoryPayloadStore[Future]
    val original = bytes("original")
    val ref      = PayloadRef.fresh(original)
    await(store.delete(ref)) shouldBe PayloadDelete.FinalDeleted
    val corrupt = "corrupt".getBytes("UTF-8")
    store.corrupt(ref, corrupt)
    corrupt(0) = 0
    await(store.get(ref)) shouldBe PayloadRead.Found(bytes("corrupt"))
    store.objectCount shouldBe 1
    await(store.put(ref, original)) shouldBe PayloadWrite.Retired
    await(store.delete(ref)) shouldBe PayloadDelete.FinalDeleted
    store.contains(ref) shouldBe false
  }

  it should "allow a fresh put after an unproven delete and preserve immutable-put conflicts" in {
    val store = new InMemoryPayloadStore[Future](CleanupBasis.Unproven)
    val value = bytes("original")
    val ref   = PayloadRef.fresh(value)
    await(store.put(ref, value)) shouldBe PayloadWrite.Durable
    await(store.put(ref, value)) shouldBe PayloadWrite.Durable
    await(store.put(ref, bytes("different"))) shouldBe PayloadWrite.Conflict
    await(store.get(ref)) shouldBe PayloadRead.Found(value)
    await(store.delete(ref)) shouldBe PayloadDelete.Uncertain
    await(store.put(ref, bytes("replacement"))) shouldBe PayloadWrite.Durable
  }
}
