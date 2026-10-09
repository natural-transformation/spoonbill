package spoonbill.snapshot

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeCheckErrors

class StateSchemaSpec extends AnyFlatSpec with Matchers:
  case class Row(id: Long, label: String, selected: Boolean) derives StateSchema
  case class Page(title: String, count: Int, note: Option[String], rows: Vector[Row]) derives StateSchema

  private def right[A](result: Either[SnapshotError, A]): A = result.fold(error => fail(error.toString), identity)
  private val page                                          = Page("Personal view", 2, Some("Draft"), Vector(Row(3L, "Alpha", true), Row(5L, "Beta", false)))
  private def id(value: String): SchemaId                   = right(SchemaId.parse(value))
  private def version(value: Int): SchemaVersion            = right(SchemaVersion.fromInt(value))

  "An opted-in state schema" should "roundtrip nested records, vectors, optional values and primitives" in {
    val schema = StateSchema[Page]
    schema.decode(right(schema.encode(page))) shouldBe Right(page)
    val empty = Page("", 0, None, Vector.empty)
    schema.decode(right(schema.encode(empty))) shouldBe Right(empty)
    StateSchema[Unit].decode(right(StateSchema[Unit].encode(()))) shouldBe Right(())
  }

  it should "describe nested field representation without including values" in {
    val shape = StateSchema[Page].shape
    shape shouldBe SchemaShape.Record(
      "Page",
      Vector(
        "title" -> SchemaShape.Text,
        "count" -> SchemaShape.Int32,
        "note"  -> SchemaShape.Optional(SchemaShape.Text),
        "rows" -> SchemaShape.VectorOf(
          SchemaShape.Record(
            "Row",
            Vector(
              "id"       -> SchemaShape.Int64,
              "label"    -> SchemaShape.Text,
              "selected" -> SchemaShape.Boolean
            )
          )
        )
      )
    )
    shape.toString should not include "Personal view"
  }

  it should "match record fields by name while rejecting missing and additional fields" in {
    val schema  = StateSchema[Page]
    val encoded = right(schema.encode(page))
    val fields  = encoded.fields.getOrElse(fail("Expected a record"))
    schema.decode(right(SnapshotValue.record(fields.reverse))) shouldBe Right(page)
    schema.decode(right(SnapshotValue.record(fields.drop(1)))) shouldBe Left(SnapshotError.RecordFieldsMismatch)
    schema.decode(right(SnapshotValue.record(fields :+ ("extra" -> SnapshotValue.unit)))) shouldBe Left(
      SnapshotError.RecordFieldsMismatch
    )
    SnapshotValue.record(Vector("x" -> SnapshotValue.unit, "x" -> SnapshotValue.unit)) shouldBe Left(
      SnapshotError.DuplicateField
    )
  }

  it should "reject wrong primitive shapes and overflowing integers" in {
    StateSchema[Int].decode(SnapshotValue.integer(Int.MaxValue.toLong + 1L)) shouldBe Left(
      SnapshotError.IntegerOutOfRange
    )
    StateSchema[Int].decode(SnapshotValue.integer(Int.MinValue.toLong - 1L)) shouldBe Left(
      SnapshotError.IntegerOutOfRange
    )
    StateSchema[Int].decode(SnapshotValue.integer(Int.MinValue.toLong)) shouldBe Right(Int.MinValue)
    StateSchema[String].decode(SnapshotValue.boolean(true)) shouldBe Left(SnapshotError.ShapeMismatch)
    StateSchema[Option[String]].decode(SnapshotValue.unit) shouldBe Left(SnapshotError.ShapeMismatch)
    StateSchema[Page].decode(right(SnapshotValue.text("record-shaped text"))) shouldBe Left(SnapshotError.ShapeMismatch)
  }

  it should "preserve explicitly mapped value invariants and redact failed record construction" in {
    final case class Count(value: Int)
    val countSchema = StateSchema[Int].imap { value =>
      if value >= 0 then Right(Count(value)) else Left(SnapshotError.InvalidValue)
    }(_.value)
    countSchema.decode(SnapshotValue.integer(-1L)) shouldBe Left(SnapshotError.InvalidValue)
    countSchema.decode(right(countSchema.encode(Count(4)))) shouldBe Right(Count(4))
    case class Positive(value: Int) derives StateSchema:
      require(value > 0, s"Rejected private value: $value")
    val invalid = right(SnapshotValue.record(Vector("value" -> SnapshotValue.integer(-42L))))
    val result  = StateSchema[Positive].decode(invalid)
    result shouldBe Left(SnapshotError.InvalidValue)
    result.toString should not include "-42"
  }

  "Snapshot bounds" should "measure UTF-8 string bytes and include record keys in total bytes" in {
    val fourBytes = right(SnapshotLimits.create(maxStringUtf8Bytes = 4))
    SnapshotValue.text("😀", fourBytes).isRight shouldBe true
    SnapshotValue.text("ééé", fourBytes) shouldBe Left(SnapshotError.StringTooLarge)
    val tinyTotal = right(SnapshotLimits.create(maxUtf8Bytes = 3))
    val text      = right(SnapshotValue.text("é"))
    SnapshotValue.record(Vector("é" -> text), tinyTotal) shouldBe Left(SnapshotError.TooManyBytes)
    StateSchema[Long].encode(1L, right(SnapshotLimits.create(maxUtf8Bytes = 7))) shouldBe Left(
      SnapshotError.TooManyBytes
    )
  }

  it should "enforce aggregate node, depth and collection budgets" in {
    val pair = right(SnapshotValue.vector(Vector(SnapshotValue.unit, SnapshotValue.unit)))
    pair.validate(right(SnapshotLimits.create(maxNodes = 2))) shouldBe Left(SnapshotError.TooManyNodes)
    pair.validate(right(SnapshotLimits.create(maxCollectionLength = 1))) shouldBe Left(SnapshotError.CollectionTooLarge)
    val nested = right(SnapshotValue.optional(Some(right(SnapshotValue.optional(Some(SnapshotValue.unit))))))
    nested.validate(right(SnapshotLimits.create(maxDepth = 2))) shouldBe Left(SnapshotError.TooDeep)
    SnapshotValue.optional(Some(nested), right(SnapshotLimits.create(maxDepth = 3))) shouldBe Left(
      SnapshotError.TooDeep
    )
    SnapshotLimits.create(maxNodes = 0) shouldBe Left(SnapshotError.InvalidConfiguration)
  }

  it should "recheck stricter decoder budgets even for values built with looser limits" in {
    val encoded = right(StateSchema[Page].encode(page))
    StateSchema[Page].decode(encoded, right(SnapshotLimits.create(maxStringUtf8Bytes = 3))) shouldBe Left(
      SnapshotError.StringTooLarge
    )
    StateSchema[Vector[Int]].encode(Vector(1, 2), right(SnapshotLimits.create(maxCollectionLength = 1))) shouldBe Left(
      SnapshotError.CollectionTooLarge
    )
    val nested = right(StateSchema[Option[Option[Int]]].encode(Some(Some(1))))
    StateSchema[Option[Option[Int]]].decode(nested, right(SnapshotLimits.create(maxDepth = 2))) shouldBe Left(
      SnapshotError.TooDeep
    )
  }

  "SnapshotFormat" should "reject unknown schema IDs and versions before structural decoding" in {
    val format   = new SnapshotFormat[Page](id("profile.page"), version(2))
    val document = right(format.write(page))
    format.read(document) shouldBe Right(page)
    format.read(document.copy(schemaId = id("different.page"))) shouldBe Left(SnapshotError.SchemaMismatch)
    format.read(document.copy(version = version(1))) shouldBe Left(SnapshotError.VersionMismatch)
    format.read(document.copy(value = SnapshotValue.unit)) shouldBe Left(SnapshotError.ShapeMismatch)
    document.toString should not include "Personal view"
    document.value.toString should not include "Personal view"
    SchemaVersion.fromInt(0) shouldBe Left(SnapshotError.InvalidConfiguration)
    SchemaId.parse("unsafe\nname") shouldBe Left(SnapshotError.InvalidConfiguration)
  }

  "Schema derivation" should "require explicit schemas for nested records" in {
    typeCheckErrors("""
      import spoonbill.snapshot.*
      case class Child(label: String)
      case class Parent(child: Child) derives StateSchema
    """) should not be empty
  }

  it should "reject secrets directly and inside supported containers" in {
    typeCheckErrors("""
      import spoonbill.snapshot.*
      case class Unsafe(secret: spoonbill.action.Secret) derives StateSchema
    """) should not be empty
    typeCheckErrors("""
      import spoonbill.snapshot.*
      case class Unsafe(secrets: Option[Vector[spoonbill.action.Secret]]) derives StateSchema
    """) should not be empty
  }

  it should "reject trusted contexts, effects and functions" in {
    typeCheckErrors("""
      import spoonbill.snapshot.*
      case class Unsafe(context: spoonbill.action.AuthenticatedActionContext[String]) derives StateSchema
    """) should not be empty
    typeCheckErrors("""
      import spoonbill.snapshot.*
      case class Unsafe(effect: scala.concurrent.Future[String]) derives StateSchema
    """) should not be empty
    typeCheckErrors("""
      import spoonbill.snapshot.*
      case class Unsafe(callback: Int => Int) derives StateSchema
    """) should not be empty
  }

  it should "provide no Any or Java Serializable fallback and no public raw-value constructor" in {
    typeCheckErrors("summon[spoonbill.snapshot.StateSchema[Any]]") should not be empty
    typeCheckErrors("summon[spoonbill.snapshot.StateSchema[java.io.Serializable]]") should not be empty
    typeCheckErrors("new spoonbill.snapshot.SnapshotValue()") should not be empty
  }
