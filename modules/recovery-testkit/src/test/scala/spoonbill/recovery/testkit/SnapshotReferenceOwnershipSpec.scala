package spoonbill.recovery.testkit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.recovery.*
import spoonbill.security.Versions.ViewRevision
import spoonbill.snapshot.ViewSnapshotError

class SnapshotReferenceOwnershipSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  "Reference read pins" should "retain the original reader's pin when a successor tries to finish it" in {
    val fixture = new InMemoryRecoveryFixture[Future, Counter](CounterFormats.current, new InMemoryPayloadStore[Future]())
    try {
      val original = await(fixture.open())
      val first = await(fixture.store(original).save(ViewRevision.initial, Counter(1))).toOption.get
      val policy = SnapshotSchemaPolicy.of(CounterFormats.current)
      val before = fixture.references.bind(original.view, policy).get
      val pin = await(before.acquireRead()) match {
        case ReferenceLoad.Pinned(value, _, _) => value
        case other => fail(s"Expected pinned read, got $other")
      }
      val successor = await(fixture.takeOver(original))
      await(fixture.store(successor).save(first, Counter(2))).isRight shouldBe true
      val after = fixture.references.bind(successor.view, policy).get
      await(after.finishRead(pin)) shouldBe Left(ViewSnapshotError.StaleOwner)
      await(fixture.references.inventory(original.view.qsid)).openPins shouldBe 1
      await(fixture.references.maintenance.claimGarbage(1)) shouldBe empty
      await(before.releaseRead(pin))
      await(fixture.references.maintenance.claimGarbage(1)).size shouldBe 1
    } finally await(fixture.close())
  }
}
