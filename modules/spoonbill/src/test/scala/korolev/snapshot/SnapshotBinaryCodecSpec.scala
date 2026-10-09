package spoonbill.snapshot

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.ByteBuffer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeCheckErrors

class SnapshotBinaryCodecSpec extends AnyFlatSpec with Matchers {
  case class Row(id: Long, selected: Boolean) derives StateSchema
  case class Presentation(title: String, page: Option[Int], rows: Vector[Row]) derives StateSchema

  private def accepted[A](value: Either[SnapshotError, A]): A = value.fold(error => fail(error.toString), identity)
  private val schemaId = accepted(SchemaId.parse("presentation"))
  private val version = accepted(SchemaVersion.fromInt(1))
  private val format = new SnapshotFormat[Presentation](schemaId, version)
  private def wire(write: DataOutputStream => Unit): Array[Byte] = {
    val buffer = new ByteArrayOutputStream()
    val output = new DataOutputStream(buffer)
    output.writeInt(0x53564231)
    val id = schemaId.value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    output.writeInt(id.length); output.write(id); output.writeInt(version.value)
    write(output)
    output.flush()
    buffer.toByteArray
  }

  "SnapshotBinaryCodec" should "roundtrip typed data without rendering or runtime objects" in {
    val state = Presentation("Résumé 😀", Some(3), Vector(Row(9L, true), Row(10L, false)))
    val bytes = accepted(SnapshotBinaryCodec.encode(accepted(format.write(state))))
    format.read(accepted(SnapshotBinaryCodec.decode(bytes))) shouldBe Right(state)
    val empty = Presentation("", None, Vector.empty)
    format.read(accepted(SnapshotBinaryCodec.decode(accepted(SnapshotBinaryCodec.encode(accepted(format.write(empty))))))) shouldBe Right(empty)
  }

  it should "reject truncation, trailing bytes and unknown encoding versions" in {
    val bytes = accepted(SnapshotBinaryCodec.encode(SnapshotDocument(schemaId, version, SnapshotValue.unit)))
    (0 until bytes.length).foreach { count => SnapshotBinaryCodec.decode(bytes.take(count)).isLeft shouldBe true }
    SnapshotBinaryCodec.decode(bytes ++ Array[Byte](0)) shouldBe Left(SnapshotError.MalformedEncoding)
    val futureVersion = bytes.clone()
    futureVersion(3) = '2'.toByte
    SnapshotBinaryCodec.decode(futureVersion) shouldBe Left(SnapshotError.UnsupportedEncodingVersion)
  }

  it should "reject enormous declared lengths before allocation" in {
    val text = wire { output => output.writeByte(1); output.writeInt(Int.MaxValue) }
    val vector = wire { output => output.writeByte(6); output.writeInt(Int.MaxValue) }
    val record = wire { output => output.writeByte(7); output.writeInt(-1) }
    List(text, vector, record).foreach(bytes => SnapshotBinaryCodec.decode(bytes) shouldBe Left(SnapshotError.MalformedEncoding))
    val header = wire(_.writeByte(0))
    ByteBuffer.wrap(header).putInt(4, Int.MaxValue)
    SnapshotBinaryCodec.decode(header) shouldBe Left(SnapshotError.MalformedEncoding)
  }

  it should "reject raw byte, depth and node budgets before constructing an unbounded tree" in {
    val bytes = wire { output => output.writeByte(5); output.writeByte(5); output.writeByte(0) }
    SnapshotBinaryCodec.decode(bytes, accepted(SnapshotLimits.create(maxDepth = 2))) shouldBe Left(SnapshotError.TooDeep)
    SnapshotBinaryCodec.decode(bytes, accepted(SnapshotLimits.create(maxNodes = 1))) shouldBe Left(SnapshotError.TooManyNodes)
    val limits = accepted(SnapshotLimits.create(maxUtf8Bytes = 4, maxNodes = 1))
    SnapshotBinaryCodec.decode(new Array[Byte](SnapshotBinaryCodec.maxEncodedBytes(limits) + 1), limits) shouldBe Left(SnapshotError.EncodedTooLarge)
    SnapshotBinaryCodec.decode(bytes, accepted(SnapshotLimits.create(maxDepth = 129))) shouldBe Left(SnapshotError.InvalidConfiguration)
  }

  it should "reject invalid UTF8, invalid tags, duplicate fields and invalid booleans" in {
    val invalidText = wire { output => output.writeByte(1); output.writeInt(1); output.writeByte(255) }
    val invalidTag = wire(_.writeByte(255))
    val invalidBoolean = wire { output => output.writeByte(3); output.writeByte(2) }
    List(invalidText, invalidTag, invalidBoolean).foreach(bytes =>
      SnapshotBinaryCodec.decode(bytes) shouldBe Left(SnapshotError.MalformedEncoding))
    val duplicate = wire { output =>
      output.writeByte(7); output.writeInt(2)
      (1 to 2).foreach { _ => output.writeInt(1); output.writeByte('x'); output.writeByte(0) }
    }
    SnapshotBinaryCodec.decode(duplicate) shouldBe Left(SnapshotError.DuplicateField)
  }

  it should "reject invalid source Unicode instead of silently replacing it" in {
    val invalid = accepted(SnapshotValue.text(new String(Array(0xd800.toChar))))
    SnapshotBinaryCodec.encode(SnapshotDocument(schemaId, version, invalid)) shouldBe Left(SnapshotError.InvalidValue)
  }

  it should "keep errors and document diagnostics free of private field content" in {
    val state = Presentation("synthetic-private-content", None, Vector.empty)
    val document = accepted(format.write(state))
    accepted(SnapshotBinaryCodec.decode(accepted(SnapshotBinaryCodec.encode(document)))).toString should not include state.title
    SnapshotBinaryCodec.decode(accepted(SnapshotBinaryCodec.encode(document)).dropRight(1)).toString should not include state.title
  }

  "Snapshot binding types" should "remain distinct metadata and have no automatic presentation schema" in {
    typeCheckErrors("""
      import spoonbill.snapshot.*
      case class Unsafe(identity: SnapshotIdentity) derives StateSchema
    """) should not be empty
    typeCheckErrors("""
      import spoonbill.snapshot.SnapshotKeys.*
      val subject: Subject = Scope.parse("scope").fold(_ => throw new IllegalArgumentException, identity)
    """) should not be empty
    SnapshotKeys.Subject.parse("") shouldBe Left(ViewSnapshotError.InvalidIdentity)
    SnapshotKeys.Scope.parse("scope\nsecret") shouldBe Left(ViewSnapshotError.InvalidIdentity)
  }
}
