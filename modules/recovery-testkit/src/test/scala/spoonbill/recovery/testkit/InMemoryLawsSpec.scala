package spoonbill.recovery.testkit

import scala.concurrent.Future
import spoonbill.effect.Effect
import spoonbill.recovery.*
import spoonbill.snapshot.{SchemaId, SchemaVersion, SnapshotFormat, StateSchema}

final case class Counter(value: Int) derives StateSchema

object CounterFormats {
  val current: SnapshotFormat[Counter] =
    new SnapshotFormat[Counter](SchemaId.parse("counter").toOption.get, SchemaVersion.fromInt(1).toOption.get)
  val incompatible: SnapshotFormat[Counter] =
    new SnapshotFormat[Counter](SchemaId.parse("counter").toOption.get, SchemaVersion.fromInt(2).toOption.get)
}

/** The modeled host with a fencing in-process adapter. */
class InMemoryFencedRecoveryLawsSpec extends RecoveryLaws[Future, Counter] {
  protected given effect: Effect[Future] = Effect.futureEffect
  protected def fixture(): RecoveryFixture[Future, Counter] =
    new InMemoryRecoveryFixture[Future, Counter](
      CounterFormats.current,
      new InMemoryPayloadStore[Future](CleanupBasis.DurableRetirementFence)
    )
  protected def sample(index: Int): Counter               = Counter(index)
  protected def incompatibleFormat: SnapshotFormat[Counter] = CounterFormats.incompatible
}

/** The modeled host with an adapter that cannot prove finality and a small obligation bound. */
class InMemoryUnprovenRecoveryLawsSpec extends RecoveryLaws[Future, Counter] {
  protected given effect: Effect[Future] = Effect.futureEffect
  protected def fixture(): RecoveryFixture[Future, Counter] =
    new InMemoryRecoveryFixture[Future, Counter](
      CounterFormats.current,
      new InMemoryPayloadStore[Future](CleanupBasis.Unproven),
      SnapshotReferenceSettings(maxOpenObligations = 4)
    )
  protected def sample(index: Int): Counter               = Counter(index)
  protected def incompatibleFormat: SnapshotFormat[Counter] = CounterFormats.incompatible
}

class InMemoryFencedPayloadStoreLawsSpec extends PayloadStoreLaws[Future] {
  protected given effect: Effect[Future]                 = Effect.futureEffect
  protected def newStore(): SnapshotPayloadStore[Future] = new InMemoryPayloadStore[Future]()
}

class InMemoryUnprovenPayloadStoreLawsSpec extends PayloadStoreLaws[Future] {
  protected given effect: Effect[Future]                 = Effect.futureEffect
  protected def newStore(): SnapshotPayloadStore[Future] = new InMemoryPayloadStore[Future](CleanupBasis.Unproven)
}
