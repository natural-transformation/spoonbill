package spoonbill.snapshot

import scala.util.control.NonFatal

final class SchemaId private (val value: String):
  override def toString: String = value

object SchemaId:
  def parse(value: String): Either[SnapshotError, SchemaId] =
    if value.matches("[a-z][a-z0-9_.-]{0,95}") then Right(new SchemaId(value))
    else Left(SnapshotError.InvalidConfiguration)

final class SchemaVersion private (val value: Int)

object SchemaVersion:
  def fromInt(value: Int): Either[SnapshotError, SchemaVersion] =
    if value > 0 then Right(new SchemaVersion(value)) else Left(SnapshotError.InvalidConfiguration)

/**
 * This envelope carries schema metadata, not authentication authority. A future
 * storage adapter must independently check owner/realm/security bindings.
 */
final case class SnapshotDocument(schemaId: SchemaId, version: SchemaVersion, value: SnapshotValue):
  override def toString: String = s"SnapshotDocument(${schemaId.value},${version.value},<redacted>)"

final class SnapshotFormat[A] private (
  val schemaId: SchemaId,
  val version: SchemaVersion,
  val limits: SnapshotLimits,
  compatible: Map[(String, Int), SnapshotValue => Either[SnapshotError, A]]
)(using val schema: StateSchema[A]):
  def this(schemaId: SchemaId, version: SchemaVersion, limits: SnapshotLimits = SnapshotLimits.default)(
    using schema: StateSchema[A]
  ) = this(schemaId, version, limits, Map.empty)(using schema)

  /** Explicit pure conversion from one declared stored format. The write format
    * stays unchanged, and loading does not rewrite durable bytes. During a roll,
    * every serving version must read the formats the other version writes.
    * Conversion runs within the new format's decoding and output limits. It
    * must be bounded and repeatable, with no I/O or business effects; a later
    * load or reconnect may run it again.
    */
  def reading[B](previous: SnapshotFormat[B])(convert: B => Either[SnapshotError, A]): SnapshotFormat[A] =
    val key = previous.schemaId.value -> previous.version.value
    require(key != (schemaId.value -> version.value), "The current snapshot format already has a decoder")
    require(!compatible.contains(key), "Duplicate snapshot compatibility decoder")
    val decode: SnapshotValue => Either[SnapshotError, A] = value =>
      try previous.schema.decode(value, limits).flatMap(convert).flatMap { result =>
        schema.encode(result, limits).map(_ => result)
      }
      catch case NonFatal(_) => Left(SnapshotError.InvalidValue)
    new SnapshotFormat(schemaId, version, limits, compatible.updated(key, decode))

  /** Metadata selection only; callers still have to validate bytes and identity. */
  def accepts(id: String, revision: Int): Boolean =
    (id == schemaId.value && revision == version.value) || compatible.contains(id -> revision)

  def write(value: A): Either[SnapshotError, SnapshotDocument] =
    schema.encode(value, limits).map(SnapshotDocument(schemaId, version, _))

  def read(document: SnapshotDocument): Either[SnapshotError, A] =
    if document.schemaId.value == schemaId.value && document.version.value == version.value then
      schema.decode(document.value, limits)
    else compatible.get(document.schemaId.value -> document.version.value) match
      case Some(decode) => document.value.validate(limits).flatMap(decode)
      case None if document.schemaId.value != schemaId.value => Left(SnapshotError.SchemaMismatch)
      case None => Left(SnapshotError.VersionMismatch)
