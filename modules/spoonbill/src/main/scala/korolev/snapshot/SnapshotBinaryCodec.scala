package spoonbill.snapshot

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream, EOFException}
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.control.NonFatal

/** Versioned data-only encoding. No Java serialization, class names, object
  * construction or arbitrary codecs enter the wire format. Metadata and payload
  * are bounded independently; errors never contain rejected bytes or strings.
  */
object SnapshotBinaryCodec {
  private val Magic = 0x53564231 // SVB1
  private val MaximumBytes = 16 * 1024 * 1024
  private val MaximumDepth = 128

  def maxEncodedBytes(limits: SnapshotLimits): Int =
    math.min(MaximumBytes.toLong, limits.maxUtf8Bytes.toLong + limits.maxNodes.toLong * 9L + 256L).toInt

  private final class Invalid(val reason: SnapshotError) extends RuntimeException
  private def invalid(reason: SnapshotError): Nothing = throw new Invalid(reason)

  def encode(document: SnapshotDocument, limits: SnapshotLimits = SnapshotLimits.default): Either[SnapshotError, Array[Byte]] =
    SnapshotLimits.create(limits.maxDepth, limits.maxNodes, limits.maxUtf8Bytes.min(MaximumBytes),
      limits.maxStringUtf8Bytes.min(MaximumBytes), limits.maxCollectionLength).flatMap(document.value.validate).flatMap { _ =>
      if (limits.maxDepth > MaximumDepth) Left(SnapshotError.InvalidConfiguration)
      else try {
        val maximum = maxEncodedBytes(limits)
        val buffer = new ByteArrayOutputStream() {
          override def write(value: Int): Unit = {
            if (count >= maximum) invalid(SnapshotError.EncodedTooLarge)
            super.write(value)
          }
          override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
            if (length < 0 || count.toLong + length > maximum) invalid(SnapshotError.EncodedTooLarge)
            super.write(bytes, offset, length)
          }
        }
        val output = new DataOutputStream(buffer)
        def text(value: String): Unit = {
          val encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
          val bytes = new Array[Byte](encoded.remaining())
          encoded.get(bytes)
          output.writeInt(bytes.length)
          output.write(bytes)
        }
        def write(value: SnapshotValue): Unit = value.node match {
          case SnapshotNode.Unit => output.writeByte(0)
          case SnapshotNode.Text(value) => output.writeByte(1); text(value)
          case SnapshotNode.Integer(value) => output.writeByte(2); output.writeLong(value)
          case SnapshotNode.Boolean(value) => output.writeByte(3); output.writeByte(if (value) 1 else 0)
          case SnapshotNode.Optional(None) => output.writeByte(4)
          case SnapshotNode.Optional(Some(value)) => output.writeByte(5); write(value)
          case SnapshotNode.Vector(values) =>
            output.writeByte(6); output.writeInt(values.size); values.foreach(write)
          case SnapshotNode.Record(fields) =>
            output.writeByte(7); output.writeInt(fields.size)
            fields.foreach { case (key, value) => text(key); write(value) }
        }
        output.writeInt(Magic)
        text(document.schemaId.value)
        output.writeInt(document.version.value)
        write(document.value)
        output.flush()
        Right(buffer.toByteArray)
      } catch {
        case error: Invalid => Left(error.reason)
        case NonFatal(_) => Left(SnapshotError.InvalidValue)
      }
    }

  def decode(bytes: Array[Byte], limits: SnapshotLimits = SnapshotLimits.default): Either[SnapshotError, SnapshotDocument] = {
    if (bytes.length > maxEncodedBytes(limits)) Left(SnapshotError.EncodedTooLarge)
    else if (limits.maxDepth > MaximumDepth) Left(SnapshotError.InvalidConfiguration)
    else try {
      val input = new DataInputStream(new ByteArrayInputStream(bytes))
      var remainingNodes = limits.maxNodes // ALLOW-VAR: bounded parser cursor, never shared.
      var remainingPayload = limits.maxUtf8Bytes.toLong // ALLOW-VAR: bounded parser budget, never shared.
      def consumePayload(count: Long): Unit = {
        if (count < 0 || count > remainingPayload) invalid(SnapshotError.TooManyBytes)
        remainingPayload -= count
      }
      def text(maximum: Int, payload: Boolean): String = {
        val count = input.readInt()
        if (count < 0 || count > maximum || count > input.available()) invalid(SnapshotError.MalformedEncoding)
        if (payload) consumePayload(count)
        val value = new Array[Byte](count)
        input.readFully(value)
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString
      }
      def length(): Int = {
        val count = input.readInt()
        // Every child requires at least a tag byte and one node. Check before
        // allocating any collection or entering its decode loop.
        if (count < 0 || count > limits.maxCollectionLength || count > remainingNodes || count > input.available())
          invalid(SnapshotError.MalformedEncoding)
        count
      }
      def checked(value: Either[SnapshotError, SnapshotValue]): SnapshotValue =
        value.fold(invalid, identity)
      def read(depth: Int): SnapshotValue = {
        if (depth > limits.maxDepth) invalid(SnapshotError.TooDeep)
        if (remainingNodes <= 0) invalid(SnapshotError.TooManyNodes)
        remainingNodes -= 1
        input.readUnsignedByte() match {
          case 0 => SnapshotValue.unit
          case 1 => checked(SnapshotValue.text(text(limits.maxStringUtf8Bytes, true), limits))
          case 2 => consumePayload(8L); SnapshotValue.integer(input.readLong())
          case 3 =>
            consumePayload(1L)
            input.readUnsignedByte() match {
              case 0 => SnapshotValue.boolean(false)
              case 1 => SnapshotValue.boolean(true)
              case _ => invalid(SnapshotError.MalformedEncoding)
            }
          case 4 => checked(SnapshotValue.optional(None, limits))
          case 5 => checked(SnapshotValue.optional(Some(read(depth + 1)), limits))
          case 6 =>
            val count = length()
            checked(SnapshotValue.vector(Vector.fill(count)(read(depth + 1)), limits))
          case 7 =>
            val count = length()
            checked(SnapshotValue.record(Vector.fill(count)(text(limits.maxStringUtf8Bytes, true) -> read(depth + 1)), limits))
          case _ => invalid(SnapshotError.MalformedEncoding)
        }
      }
      val magic = input.readInt()
      if (magic != Magic) {
        if ((magic >>> 8) == (Magic >>> 8)) invalid(SnapshotError.UnsupportedEncodingVersion)
        else invalid(SnapshotError.MalformedEncoding)
      }
      val schema = SchemaId.parse(text(96, false)).fold(_ => invalid(SnapshotError.MalformedEncoding), identity)
      val version = SchemaVersion.fromInt(input.readInt()).fold(_ => invalid(SnapshotError.MalformedEncoding), identity)
      val value = read(1)
      if (input.available() != 0) invalid(SnapshotError.MalformedEncoding)
      value.validate(limits).fold(invalid, _ => ())
      Right(SnapshotDocument(schema, version, value))
    } catch {
      case error: Invalid => Left(error.reason)
      case _: EOFException => Left(SnapshotError.MalformedEncoding)
      case NonFatal(_) => Left(SnapshotError.MalformedEncoding)
    }
  }
}
