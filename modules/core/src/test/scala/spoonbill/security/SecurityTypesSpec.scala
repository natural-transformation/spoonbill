package spoonbill.security

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeChecks

class SecurityTypesSpec extends AnyFlatSpec with Matchers {
  "Security identifiers" should "reject session IDs where an invocation is required" in {
    typeChecks("""
      import spoonbill.security.Identifiers.*
      val session = AuthSessionId.fromUuid(new java.util.UUID(0L, 1L))
      val invocation: InvocationId = session
    """) shouldBe false
  }

  it should "separate slot generation from security generation and owner epoch" in {
    typeChecks("""
      import spoonbill.security.Versions.*
      val epoch: ViewOwnershipEpoch = SlotGeneration.initial
    """) shouldBe false
    typeChecks("""
      import spoonbill.security.Versions.*
      val generation: SecurityGeneration = SlotGeneration.initial
    """) shouldBe false
    typeChecks("""
      import spoonbill.security.Versions.*
      val revision: ViewRevision = ViewOwnershipEpoch.initial
    """) shouldBe false
  }

  it should "reject raw numbers as validated versions" in {
    typeChecks("""
      import spoonbill.security.Versions.*
      val epoch: ViewOwnershipEpoch = 1L
    """) shouldBe false
  }
}
