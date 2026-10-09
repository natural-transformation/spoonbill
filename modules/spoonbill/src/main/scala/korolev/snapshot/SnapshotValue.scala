package spoonbill.snapshot

import java.nio.charset.StandardCharsets

enum SnapshotError:
  case InvalidConfiguration, StringTooLarge, CollectionTooLarge, TooDeep, TooManyNodes, TooManyBytes
  case DuplicateField, ShapeMismatch, RecordFieldsMismatch, IntegerOutOfRange, InvalidValue
  case SchemaMismatch, VersionMismatch
  case MalformedEncoding, UnsupportedEncodingVersion, EncodedTooLarge

/**
 * Payload budgets count UTF-8 text/keys and fixed-width scalar data, not
 * encoded framing. Any future wire adapter must bound raw bytes and nesting
 * before constructing this value tree.
 */
final class SnapshotLimits private (
  val maxDepth: Int,
  val maxNodes: Int,
  val maxUtf8Bytes: Int,
  val maxStringUtf8Bytes: Int,
  val maxCollectionLength: Int
)

object SnapshotLimits:
  val default: SnapshotLimits = new SnapshotLimits(32, 10000, 1024 * 1024, 64 * 1024, 1024)

  def create(
    maxDepth: Int = 32,
    maxNodes: Int = 10000,
    maxUtf8Bytes: Int = 1024 * 1024,
    maxStringUtf8Bytes: Int = 64 * 1024,
    maxCollectionLength: Int = 1024
  ): Either[SnapshotError, SnapshotLimits] =
    if List(maxDepth, maxNodes, maxUtf8Bytes, maxStringUtf8Bytes, maxCollectionLength).exists(_ <= 0) then
      Left(SnapshotError.InvalidConfiguration)
    else Right(new SnapshotLimits(maxDepth, maxNodes, maxUtf8Bytes, maxStringUtf8Bytes, maxCollectionLength))

enum SnapshotKind:
  case Text, Integer, Boolean, Unit, Optional, Vector, Record

private[snapshot] enum SnapshotNode:
  case Text(value: String)
  case Integer(value: Long)
  case Boolean(value: scala.Boolean)
  case Unit
  case Optional(value: Option[SnapshotValue])
  case Vector(values: scala.collection.immutable.Vector[SnapshotValue])
  case Record(fields: scala.collection.immutable.Vector[(String, SnapshotValue)])

/**
 * Immutable, checked snapshot data. Constructors are closed; the factories
 * validate aggregate depth/node/byte budgets, including already-built children.
 * Inspection is explicit and diagnostics never print payload values.
 */
final class SnapshotValue private (
  private[snapshot] val node: SnapshotNode,
  private val depth: Long,
  private val nodes: Long,
  private val bytes: Long,
  private val largestString: Long,
  private val largestCollection: Long
):
  def kind: SnapshotKind = node match
    case SnapshotNode.Text(_)     => SnapshotKind.Text
    case SnapshotNode.Integer(_)  => SnapshotKind.Integer
    case SnapshotNode.Boolean(_)  => SnapshotKind.Boolean
    case SnapshotNode.Unit        => SnapshotKind.Unit
    case SnapshotNode.Optional(_) => SnapshotKind.Optional
    case SnapshotNode.Vector(_)   => SnapshotKind.Vector
    case SnapshotNode.Record(_)   => SnapshotKind.Record

  def text: Option[String] = node match
    case SnapshotNode.Text(value) => Some(value)
    case _                        => None

  def fields: Option[Vector[(String, SnapshotValue)]] = node match
    case SnapshotNode.Record(values) => Some(values)
    case _                           => None

  def items: Option[Vector[SnapshotValue]] = node match
    case SnapshotNode.Vector(values) => Some(values)
    case _                           => None

  def validate(limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] =
    if depth > limits.maxDepth then Left(SnapshotError.TooDeep)
    else if nodes > limits.maxNodes then Left(SnapshotError.TooManyNodes)
    else if largestString > limits.maxStringUtf8Bytes then Left(SnapshotError.StringTooLarge)
    else if largestCollection > limits.maxCollectionLength then Left(SnapshotError.CollectionTooLarge)
    else if bytes > limits.maxUtf8Bytes then Left(SnapshotError.TooManyBytes)
    else Right(this)

  override def toString: String = s"SnapshotValue($kind,<redacted>)"

object SnapshotValue:
  private def leaf(node: SnapshotNode, bytes: Long, stringBytes: Long = 0L): SnapshotValue =
    new SnapshotValue(node, 1L, 1L, bytes, stringBytes, 0L)

  def text(value: String, limits: SnapshotLimits = SnapshotLimits.default): Either[SnapshotError, SnapshotValue] =
    utf8Size(value, limits).flatMap(bytes => leaf(SnapshotNode.Text(value), bytes, bytes).validate(limits))

  def integer(value: Long): SnapshotValue    = leaf(SnapshotNode.Integer(value), 8L)
  def boolean(value: Boolean): SnapshotValue = leaf(SnapshotNode.Boolean(value), 1L)
  val unit: SnapshotValue                    = leaf(SnapshotNode.Unit, 0L)

  def optional(
    value: Option[SnapshotValue],
    limits: SnapshotLimits = SnapshotLimits.default
  ): Either[SnapshotError, SnapshotValue] =
    container(SnapshotNode.Optional(value), value.toVector, 0L, 0L, limits)

  def vector(
    values: Vector[SnapshotValue],
    limits: SnapshotLimits = SnapshotLimits.default
  ): Either[SnapshotError, SnapshotValue] =
    if values.size > limits.maxCollectionLength then Left(SnapshotError.CollectionTooLarge)
    else container(SnapshotNode.Vector(values), values, 0L, 0L, limits)

  def record(
    fields: Vector[(String, SnapshotValue)],
    limits: SnapshotLimits = SnapshotLimits.default
  ): Either[SnapshotError, SnapshotValue] =
    if fields.size > limits.maxCollectionLength then Left(SnapshotError.CollectionTooLarge)
    else if fields.map(_._1).distinct.size != fields.size then Left(SnapshotError.DuplicateField)
    else
      val keyBudget = fields.foldLeft[Either[SnapshotError, (Long, Long)]](Right((0L, 0L))) { case (result, (key, _)) =>
        result.flatMap { (total, largest) =>
          utf8Size(key, limits).flatMap { size =>
            val next = total + size
            if next > limits.maxUtf8Bytes then Left(SnapshotError.TooManyBytes)
            else Right((next, largest.max(size)))
          }
        }
      }
      keyBudget.flatMap { (total, largest) =>
        container(SnapshotNode.Record(fields), fields.map(_._2), total, largest, limits)
      }

  private def utf8Size(value: String, limits: SnapshotLimits): Either[SnapshotError, Long] =
    if value.length > limits.maxStringUtf8Bytes then Left(SnapshotError.StringTooLarge)
    else if value.length > limits.maxUtf8Bytes then Left(SnapshotError.TooManyBytes)
    else
      val bytes = value.getBytes(StandardCharsets.UTF_8).length.toLong
      if bytes > limits.maxStringUtf8Bytes then Left(SnapshotError.StringTooLarge)
      else if bytes > limits.maxUtf8Bytes then Left(SnapshotError.TooManyBytes)
      else Right(bytes)

  private def container(
    node: SnapshotNode,
    children: Vector[SnapshotValue],
    ownBytes: Long,
    ownLargestString: Long,
    limits: SnapshotLimits
  ): Either[SnapshotError, SnapshotValue] =
    val totals = children.foldLeft((1L, 1L, ownBytes, ownLargestString, children.size.toLong)) {
      case ((depth, nodes, bytes, largestString, largestCollection), child) =>
        (
          depth.max(child.depth + 1L),
          nodes + child.nodes,
          bytes + child.bytes,
          largestString.max(child.largestString),
          largestCollection.max(child.largestCollection)
        )
    }
    val (depth, nodes, bytes, largestString, largestCollection) = totals
    new SnapshotValue(node, depth, nodes, bytes, largestString, largestCollection).validate(limits)
