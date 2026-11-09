import java.io.{ByteArrayOutputStream, IOException}
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
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
  // A small bound lets the capacity laws run against this adapter as well.
  protected def fixture(): RecoveryFixture[Future, Draft] =
    new InMemoryRecoveryFixture[Future, Draft](Drafts.format, Drafts.store(), SnapshotReferenceSettings(maxOpenObligations = 4))
  protected def sample(index: Int): Draft               = Draft(s"draft-$index")
  protected def incompatibleFormat: SnapshotFormat[Draft] = Drafts.incompatible
}

class PayloadFileIOSpec extends AnyFunSuite {
  test("short writes preserve every byte before completing") {
    val expected = Array.tabulate[Byte](17)(_.toByte)
    val written = new ByteArrayOutputStream()
    var calls = 0
    val channel = new WritableByteChannel {
      def isOpen: Boolean = true
      def close(): Unit = ()
      def write(buffer: ByteBuffer): Int = {
        calls += 1
        val length = math.min(3, buffer.remaining())
        val chunk = new Array[Byte](length)
        buffer.get(chunk)
        written.write(chunk)
        length
      }
    }

    PayloadFileIO.writeFully(channel, ByteBuffer.wrap(expected))

    assert(calls > 1)
    assert(written.toByteArray.sameElements(expected))
  }

  test("a stalled write fails instead of acknowledging incomplete bytes or spinning") {
    var calls = 0
    val channel = new WritableByteChannel {
      def isOpen: Boolean = true
      def close(): Unit = ()
      def write(buffer: ByteBuffer): Int = {
        calls += 1
        if (calls == 1) {
          buffer.get()
          1
        } else 0
      }
    }
    val buffer = ByteBuffer.wrap(Array[Byte](1, 2, 3))

    intercept[IOException](PayloadFileIO.writeFully(channel, buffer))

    assert(calls == 2)
    assert(buffer.remaining() == 2)
  }
}
