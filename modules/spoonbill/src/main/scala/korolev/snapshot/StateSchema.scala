package spoonbill.snapshot

import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror
import scala.util.control.NonFatal

enum SchemaShape:
  case Text, Int32, Int64, Boolean, Unit
  case Optional(element: SchemaShape)
  case VectorOf(element: SchemaShape)
  case Record(name: String, fields: Vector[(String, SchemaShape)])

/**
 * Opt-in presentation snapshots. No schema is inferred for arbitrary products,
 * Any, Serializable, secrets, trusted contexts, effects or functions. A custom
 * mapping is trusted application code; an ordinary String may still hold data
 * the application should not persist. This is not a semantic secrecy checker.
 *
 * This foundation is not connected to the existing
 * StateStorage/StateSerializer.
 */
sealed abstract class StateSchema[A]:
  def shape: SchemaShape
  protected def write(value: A, limits: SnapshotLimits): Either[SnapshotError, SnapshotValue]
  protected def read(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, A]

  final def encode(
    value: A,
    limits: SnapshotLimits = SnapshotLimits.default
  ): Either[SnapshotError, SnapshotValue] = write(value, limits).flatMap(_.validate(limits))

  final def decode(
    value: SnapshotValue,
    limits: SnapshotLimits = SnapshotLimits.default
  ): Either[SnapshotError, A] = value.validate(limits).flatMap(read(_, limits))

  final def imap[B](to: A => Either[SnapshotError, B])(from: B => A): StateSchema[B] =
    val self = this
    new StateSchema[B]:
      val shape: SchemaShape = self.shape
      protected def write(value: B, limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] =
        self.encode(from(value), limits)
      protected def read(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, B] =
        self.decode(value, limits).flatMap(to)

object StateSchema:
  def apply[A](using schema: StateSchema[A]): StateSchema[A] = schema

  given StateSchema[String] with
    val shape: SchemaShape = SchemaShape.Text
    protected def write(value: String, limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] =
      SnapshotValue.text(value, limits)
    protected def read(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, String] =
      value.text.toRight(SnapshotError.ShapeMismatch)

  given StateSchema[Long] with
    val shape: SchemaShape = SchemaShape.Int64
    protected def write(value: Long, limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] =
      Right(SnapshotValue.integer(value))
    protected def read(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, Long] = value.node match
      case SnapshotNode.Integer(number) => Right(number)
      case _                            => Left(SnapshotError.ShapeMismatch)

  given StateSchema[Int] with
    val shape: SchemaShape = SchemaShape.Int32
    protected def write(value: Int, limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] =
      Right(SnapshotValue.integer(value.toLong))
    protected def read(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, Int] = value.node match
      case SnapshotNode.Integer(number) =>
        if number >= Int.MinValue && number <= Int.MaxValue then Right(number.toInt)
        else Left(SnapshotError.IntegerOutOfRange)
      case _ => Left(SnapshotError.ShapeMismatch)

  given StateSchema[Boolean] with
    val shape: SchemaShape = SchemaShape.Boolean
    protected def write(value: Boolean, limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] =
      Right(SnapshotValue.boolean(value))
    protected def read(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, Boolean] = value.node match
      case SnapshotNode.Boolean(flag) => Right(flag)
      case _                          => Left(SnapshotError.ShapeMismatch)

  given StateSchema[Unit] with
    val shape: SchemaShape = SchemaShape.Unit
    protected def write(value: Unit, limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] = Right(
      SnapshotValue.unit
    )
    protected def read(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, Unit] = value.node match
      case SnapshotNode.Unit => Right(())
      case _                 => Left(SnapshotError.ShapeMismatch)

  given [A](using element: StateSchema[A]): StateSchema[Option[A]] with
    val shape: SchemaShape = SchemaShape.Optional(element.shape)
    protected def write(value: Option[A], limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] = value match
      case None       => SnapshotValue.optional(None, limits)
      case Some(item) => element.encode(item, limits).flatMap(encoded => SnapshotValue.optional(Some(encoded), limits))
    protected def read(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, Option[A]] =
      value.node match
        case SnapshotNode.Optional(None)       => Right(None)
        case SnapshotNode.Optional(Some(item)) => element.decode(item, limits).map(Some(_))
        case _                                 => Left(SnapshotError.ShapeMismatch)

  given [A](using element: StateSchema[A]): StateSchema[Vector[A]] with
    val shape: SchemaShape = SchemaShape.VectorOf(element.shape)
    protected def write(values: Vector[A], limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] =
      if values.size > limits.maxCollectionLength then Left(SnapshotError.CollectionTooLarge)
      else traverse(values)(element.encode(_, limits)).flatMap(SnapshotValue.vector(_, limits))
    protected def read(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, Vector[A]] =
      value.node match
        case SnapshotNode.Vector(items) => traverse(items)(element.decode(_, limits))
        case _                          => Left(SnapshotError.ShapeMismatch)

  /**
   * Nested records require their own explicit given/derives declaration. This
   * initial eager derivation supports finite, acyclic schema definitions;
   * recursive record schemas require a future explicit reference mechanism.
   */
  inline def derived[A <: Product](using mirror: Mirror.ProductOf[A]): StateSchema[A] =
    productSchema[A](
      constValue[mirror.MirroredLabel].toString,
      labels[mirror.MirroredElemLabels],
      fieldCodecs[mirror.MirroredElemTypes],
      mirror
    )

  private inline def labels[Labels <: Tuple]: Vector[String] = inline erasedValue[Labels] match
    case _: EmptyTuple     => Vector.empty
    case _: (head *: tail) => constValue[head].toString +: labels[tail]

  private trait FieldCodec:
    def shape: SchemaShape
    def encode(value: Any, limits: SnapshotLimits): Either[SnapshotError, SnapshotValue]
    def decode(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, Any]

  private def fieldCodec[A](schema: StateSchema[A]): FieldCodec = new FieldCodec:
    val shape: SchemaShape = schema.shape
    def encode(value: Any, limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] =
      // Mirror supplies exactly the corresponding product element. Erasure is
      // confined to this internal bridge, never exposed as an Any schema.
      schema.encode(value.asInstanceOf[A], limits)
    def decode(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, Any] = schema.decode(value, limits)

  private inline def fieldCodecs[Elements <: Tuple]: Vector[FieldCodec] = inline erasedValue[Elements] match
    case _: EmptyTuple     => Vector.empty
    case _: (head *: tail) => fieldCodec(summonInline[StateSchema[head]]) +: fieldCodecs[tail]

  private def productSchema[A <: Product](
    name: String,
    names: Vector[String],
    codecs: Vector[FieldCodec],
    mirror: Mirror.ProductOf[A]
  ): StateSchema[A] = new StateSchema[A]:
    val shape: SchemaShape = SchemaShape.Record(name, names.zip(codecs.map(_.shape)))
    protected def write(value: A, limits: SnapshotLimits): Either[SnapshotError, SnapshotValue] =
      if names.size > limits.maxCollectionLength then Left(SnapshotError.CollectionTooLarge)
      else
        traverse(names.indices.toVector) { index =>
          codecs(index).encode(value.productElement(index), limits).map(names(index) -> _)
        }.flatMap(SnapshotValue.record(_, limits))
    protected def read(value: SnapshotValue, limits: SnapshotLimits): Either[SnapshotError, A] = value.node match
      case SnapshotNode.Record(fields) =>
        if fields.map(_._1).toSet != names.toSet then Left(SnapshotError.RecordFieldsMismatch)
        else
          val byName = fields.toMap
          traverse(names.indices.toVector)(index => codecs(index).decode(byName(names(index)), limits)).flatMap {
            values =>
              try Right(mirror.fromProduct(Tuple.fromArray(values.toArray)))
              catch case NonFatal(_) => Left(SnapshotError.InvalidValue)
          }
      case _ => Left(SnapshotError.ShapeMismatch)

  private def traverse[A, B](values: Vector[A])(f: A => Either[SnapshotError, B]): Either[SnapshotError, Vector[B]] =
    values.foldLeft[Either[SnapshotError, Vector[B]]](Right(Vector.empty)) { (result, value) =>
      result.flatMap(items => f(value).map(items :+ _))
    }
