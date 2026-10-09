package spoonbill.action

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeCheckErrors

class InputSchemaSpec extends AnyFlatSpec with Matchers:
  private def field(value: String): FieldName = FieldName.parse(value).fold(error => fail(error.toString), identity)
  private def code(value: String): ValidationCode =
    ValidationCode.parse(value).fold(error => fail(error.toString), identity)

  "InputSchema" should "decode a complete typed form from one submitted field collection" in {
    case class Login(email: String, password: Secret, remember: Boolean)
    val schema = InputSchema
      .text(field("email"), 100)
      .zip(InputSchema.secret(field("password"), 128))
      .zip(InputSchema.checked(field("remember")))
      .map { case ((email, password), remember) => Login(email, password, remember) }
    val decoded = schema
      .decode(Vector("remember" -> "false", "password" -> "not-a-real-secret", "email" -> "a@example.test"))
      .fold(error => fail(error.toString), identity)
    decoded.email shouldBe "a@example.test"
    decoded.remember shouldBe false
    decoded.password.withValue(_.length) shouldBe 17
    decoded.toString should not include "not-a-real-secret"
    schema.fields.map(_.kind) shouldBe Vector(InputKind.Text, InputKind.Secret, InputKind.Checkbox)
  }

  it should "reject duplicate and undeclared fields instead of silently selecting a value" in {
    val schema = InputSchema.text(field("name"), 20)
    schema.decode(Vector("name" -> "first", "name" -> "second")) shouldBe Left(InputError.DuplicateField)
    schema.decode(Vector("admin" -> "true")) shouldBe Left(InputError.UnexpectedField)
    schema.decode(Vector.empty) shouldBe Left(InputError.Missing(field("name")))
  }

  it should "bound UTF-8 bytes rather than character count" in {
    val schema = InputSchema.text(field("text"), 4)
    schema.decode(Vector("text" -> "😀")) shouldBe Right("😀")
    schema.decode(Vector("text" -> "😀a")) shouldBe Left(InputError.FieldTooLarge(field("text")))
    schema.decode(Vector("text" -> "ééé")) shouldBe Left(InputError.FieldTooLarge(field("text")))
  }

  it should "bound total bytes and field count before value conversion" in {
    val schema    = InputSchema.text(field("a"), 20).zip(InputSchema.text(field("b"), 20))
    val oneField  = InputLimits.create(1, 100).fold(error => fail(error.toString), identity)
    val fiveBytes = InputLimits.create(2, 5).fold(error => fail(error.toString), identity)
    schema.decode(Vector("a" -> "a", "b" -> "b"), oneField) shouldBe Left(InputError.TooManyFields)
    schema.decode(Vector("a" -> "é", "b" -> "é"), fiveBytes) shouldBe Left(InputError.PayloadTooLarge)
    InputLimits.create(0, 10) shouldBe Left(InputConfigurationError.InvalidLimit)
  }

  it should "reject malformed booleans and preserve safe validation codes" in {
    InputSchema.checked(field("ok")).decode(Vector("ok" -> "yes")) shouldBe Left(InputError.InvalidBoolean(field("ok")))
    val required = code("name.required")
    val schema   = InputSchema.text(field("name"), 20).validate(required)(_.trim.nonEmpty)
    schema.decode(Vector("name" -> "  ")) shouldBe Left(InputError.InvalidValue(required))
  }

  it should "never put rejected values or unknown field names in framework errors" in {
    val schema  = InputSchema.secret(field("password"), 3)
    val tooLong = schema.decode(Vector("password" -> "top-secret"))
    tooLong.toString should not include "top-secret"
    schema.decode(Vector("secret-in-key" -> "top-secret")).toString should not include "secret-in-key"
    schema.decode(Vector("password" -> "ok", "password" -> "x")).toString should not include "ok"
  }

  it should "fail duplicate schema definitions at configuration time" in {
    val schema = InputSchema.text(field("name"), 20)
    intercept[IllegalArgumentException](schema.zip(schema))
  }

  "Secret" should "have a redacted representation and no product-derived codec surface" in {
    val secret = InputSchema
      .secret(field("password"), 20)
      .decode(Vector("password" -> "private"))
      .fold(error => fail(error.toString), identity)
    secret.toString shouldBe "Secret(<redacted>)"
    secret.withValue(_ == "private") shouldBe true
    typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[spoonbill.action.Secret]]") should not be empty
    typeCheckErrors("new spoonbill.action.Secret(\"private\")") should not be empty
    typeCheckErrors("val value: spoonbill.action.Secret = \"private\"") should not be empty
  }

  "Trusted contexts" should "not be constructible or implementable by application handlers" in {
    val forgedContext = typeCheckErrors("""
      new spoonbill.action.AuthenticatedActionContext[String] {
        val invocationId = spoonbill.security.Identifiers.InvocationId.fromUuid(new java.util.UUID(0L, 1L))
        val principal = "forged"
      }
    """)
    forgedContext should not be empty
    forgedContext.exists(_.message.contains("sealed")) shouldBe true
    typeCheckErrors("spoonbill.action.AuthenticatedActionContext(\"forged\")") should not be empty
    typeCheckErrors("new spoonbill.action.ActionName(\"login\")") should not be empty
  }

  "Actions" should "require an explicit access policy" in {
    val errors = typeCheckErrors("""
      import spoonbill.action.*
      val actions = new Actions[Option, Int, Unit]
      val name = ActionName.parse("increment").fold(_ => throw new IllegalArgumentException, identity)
      actions.public(name, InputSchema.empty)((_, _) => Some(UiOutcome.update[Int](_ + 1)))
    """)
    errors should not be empty
    errors.exists(_.message.contains("policy")) shouldBe true
  }

  "Names" should "reject unsafe or unbounded configuration strings" in {
    FieldName.parse("password\nvalue") shouldBe Left(InputConfigurationError.InvalidName)
    ActionName.parse("x" * 97) shouldBe Left(InputConfigurationError.InvalidName)
    ActionName.parse("todo.toggle").map(_.value) shouldBe Right("todo.toggle")
  }
