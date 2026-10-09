package spoonbill.snapshot

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

final class SnapshotFormat[A](
  val schemaId: SchemaId,
  val version: SchemaVersion,
  val limits: SnapshotLimits = SnapshotLimits.default
)(using val schema: StateSchema[A]):
  def write(value: A): Either[SnapshotError, SnapshotDocument] =
    schema.encode(value, limits).map(SnapshotDocument(schemaId, version, _))

  def read(document: SnapshotDocument): Either[SnapshotError, A] =
    if document.schemaId.value != schemaId.value then Left(SnapshotError.SchemaMismatch)
    else if document.version.value != version.value then Left(SnapshotError.VersionMismatch)
    else schema.decode(document.value, limits)
