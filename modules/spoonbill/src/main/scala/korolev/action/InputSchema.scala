package spoonbill.action

import java.nio.charset.StandardCharsets

/** Configuration errors contain no submitted values. */
enum InputConfigurationError:
  case InvalidName, InvalidLimit

final class FieldName private (val value: String):
  override def toString: String = value
  override def equals(other: Any): Boolean = other match
    case that: FieldName => value == that.value
    case _               => false
  override def hashCode(): Int = value.hashCode

object FieldName:
  def parse(value: String): Either[InputConfigurationError, FieldName] =
    if value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") then Right(new FieldName(value))
    else Left(InputConfigurationError.InvalidName)

final class ValidationCode private (val value: String):
  override def toString: String = value

object ValidationCode:
  def parse(value: String): Either[InputConfigurationError, ValidationCode] =
    if value.matches("[a-z][a-z0-9_.-]{0,63}") then Right(new ValidationCode(value))
    else Left(InputConfigurationError.InvalidName)

/**
 * Deliberately not Product or Serializable, and has no codec or extractor.
 * withValue is an explicit trusted-code escape; it cannot guarantee JVM erasure
 * or prevent the callback from retaining the value.
 */
final class Secret private (private val plaintext: String):
  def withValue[A](f: String => A): A = f(plaintext)
  override def toString: String       = "Secret(<redacted>)"

object Secret:
  private[action] def fromInput(value: String): Secret = new Secret(value)

enum InputKind:
  case Text, Secret, Checkbox

final case class InputField private[action] (name: FieldName, maxUtf8Bytes: Int, kind: InputKind)

final class InputLimits private (val maxFields: Int, val maxUtf8Bytes: Int)

object InputLimits:
  val default: InputLimits = new InputLimits(32, 64 * 1024)

  def create(maxFields: Int, maxUtf8Bytes: Int): Either[InputConfigurationError, InputLimits] =
    if maxFields > 0 && maxUtf8Bytes > 0 then Right(new InputLimits(maxFields, maxUtf8Bytes))
    else Left(InputConfigurationError.InvalidLimit)

enum InputError:
  case TooManyFields, PayloadTooLarge, DuplicateField, UnexpectedField
  case Missing(field: FieldName)
  case FieldTooLarge(field: FieldName)
  case InvalidBoolean(field: FieldName)
  case InvalidValue(code: ValidationCode)

/**
 * A declared, bounded form input. This API decodes already parsed fields;
 * transports must enforce raw frame and parser limits before allocating them.
 * Neither this schema nor its errors retain rejected values.
 */
final class InputSchema[I] private (
  val fields: Vector[InputField],
  private val read: Map[String, String] => Either[InputError, I]
):
  def map[J](f: I => J): InputSchema[J] =
    new InputSchema(fields, values => read(values).map(f))

  def zip[J](other: InputSchema[J]): InputSchema[(I, J)] =
    val all = fields ++ other.fields
    require(all.map(_.name.value).distinct.size == all.size, "Duplicate schema field declaration")
    new InputSchema(all, values => read(values).flatMap(i => other.read(values).map(j => (i, j))))

  def validate(code: ValidationCode)(predicate: I => Boolean): InputSchema[I] =
    new InputSchema(
      fields,
      values =>
        read(values).flatMap { value =>
          if predicate(value) then Right(value) else Left(InputError.InvalidValue(code))
        }
    )

  def decode(
    submitted: Vector[(String, String)],
    limits: InputLimits = InputLimits.default
  ): Either[InputError, I] =
    if submitted.size > limits.maxFields then Left(InputError.TooManyFields)
    else
      val declared = fields.map(field => field.name.value -> field).toMap
      val collected = submitted.foldLeft[Either[InputError, (Map[String, String], Long)]](Right((Map.empty, 0L))) {
        case (result, (name, value)) =>
          result.flatMap { (values, used) =>
            if values.contains(name) then Left(InputError.DuplicateField)
            else
              declared.get(name) match
                case None        => Left(InputError.UnexpectedField)
                case Some(field) =>
                  // UTF-16 length is a cheap lower bound, avoiding a large encoding allocation.
                  if value.length > field.maxUtf8Bytes then Left(InputError.FieldTooLarge(field.name))
                  else if used + name.length.toLong + value.length > limits.maxUtf8Bytes then
                    Left(InputError.PayloadTooLarge)
                  else
                    val bytes = value.getBytes(StandardCharsets.UTF_8).length
                    val total = used + name.length.toLong + bytes
                    if bytes > field.maxUtf8Bytes then Left(InputError.FieldTooLarge(field.name))
                    else if total > limits.maxUtf8Bytes then Left(InputError.PayloadTooLarge)
                    else Right((values.updated(name, value), total))
          }
      }
      collected.flatMap((values, _) => read(values))

object InputSchema:
  val empty: InputSchema[Unit] = new InputSchema(Vector.empty, _ => Right(()))

  def text(name: FieldName, maxUtf8Bytes: Int): InputSchema[String] =
    required(name, maxUtf8Bytes, InputKind.Text)

  def secret(name: FieldName, maxUtf8Bytes: Int): InputSchema[Secret] =
    required(name, maxUtf8Bytes, InputKind.Secret).map(Secret.fromInput)

  /**
   * The browser binding sends exactly "true" or "false", including unchecked
   * controls.
   */
  def checked(name: FieldName): InputSchema[Boolean] =
    val field = InputField(name, 5, InputKind.Checkbox)
    new InputSchema(
      Vector(field),
      values =>
        values.get(name.value) match
          case Some("true")  => Right(true)
          case Some("false") => Right(false)
          case Some(_)       => Left(InputError.InvalidBoolean(name))
          case None          => Left(InputError.Missing(name))
    )

  private def required(name: FieldName, maxUtf8Bytes: Int, kind: InputKind): InputSchema[String] =
    require(maxUtf8Bytes > 0, "Input field byte limit must be positive")
    new InputSchema(
      Vector(InputField(name, maxUtf8Bytes, kind)),
      values => values.get(name.value).toRight(InputError.Missing(name))
    )
