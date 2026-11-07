import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.effect.Effect
import spoonbill.recovery.*
import spoonbill.recovery.testkit.*
import spoonbill.snapshot.{SchemaId, SchemaVersion, SnapshotFormat, StateSchema}

final case class Draft(text: String) derives StateSchema

object Drafts {
  val format: SnapshotFormat[Draft] =
    new SnapshotFormat[Draft](SchemaId.parse("consumer.draft").toOption.get, SchemaVersion.fromInt(1).toOption.get)
  val incompatible: SnapshotFormat[Draft] =
    new SnapshotFormat[Draft](SchemaId.parse("consumer.draft").toOption.get, SchemaVersion.fromInt(2).toOption.get)
  def store(): FilePayloadStore = {
    given ExecutionContext = ExecutionContext.global
    new FilePayloadStore(Files.createTempDirectory("spoonbill-file-payloads"))
  }
}

/** The adapter's own contract from the published kit. */
class FilePayloadStoreLawsSpec extends PayloadStoreLaws[Future] {
  protected given effect: Effect[Future]                 = Effect.futureEffect
  protected def newStore(): SnapshotPayloadStore[Future] = Drafts.store()
}

/** The protocol laws on the kit's modeled host with this adapter as the payload store. */
class FilePayloadRecoveryLawsSpec extends RecoveryLaws[Future, Draft] {
  protected given effect: Effect[Future] = Effect.futureEffect
  protected def fixture(): RecoveryFixture[Future, Draft] =
    new InMemoryRecoveryFixture[Future, Draft](Drafts.format, Drafts.store())
  protected def sample(index: Int): Draft               = Draft(s"draft-$index")
  protected def incompatibleFormat: SnapshotFormat[Draft] = Drafts.incompatible
}
