package replacementconsumer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeChecks

/** Compile-time checks deliberately outside spoonbill's trusted namespaces. */
class ReplacementProviderBoundarySpec extends AnyFlatSpec with Matchers {
  "The replacement provider boundary" should "expose the metadata and transport inputs needed by external providers" in {
    typeChecks("""
      import spoonbill.auth.*
      def inspect(
        request: BrowserReplacementRequest,
        invocation: BrowserReplacementInvocation,
        handoff: CommittedReplacement,
        delivery: BrowserReplacementDelivery
      ): Unit = {
        val binding: String = request.binding
        val token: Option[String] = request.token
        val lookup: Option[CeremonyId] = request.ceremonyId
        val original: BrowserAuthorityStamp = invocation.authority
        val owner: spoonbill.security.ViewFence = invocation.fence
        val connection: spoonbill.security.Identifiers.ConnectionId = invocation.connectionId
        val committedOriginal: BrowserAuthorityStamp = handoff.authority
        val committedOwner: spoonbill.security.ViewFence = handoff.fence
        val committedConnection: spoonbill.security.Identifiers.ConnectionId = handoff.connectionId
        val ceremony: CeremonyId = handoff.ceremonyId
        val completion: java.util.UUID = handoff.completionId
        val deadline: java.time.Instant = handoff.expiresAt
        val deliveryLookup: java.util.UUID = delivery.completionId
        ()
      }
    """) shouldBe true
  }

  it should "allow trusted provider issuance through its own capability scope" in {
    typeChecks("""
      import spoonbill.auth.*
      def issue(
        scope: BrowserCapabilityScope[String],
        view: BrowserView[String],
        invocation: BrowserReplacementInvocation,
        request: BrowserReplacementRequest,
        id: java.util.UUID,
        deadline: java.time.Instant
      ): Unit = {
        val ceremony = scope.replacementCeremony(view, CeremonyId(id), "replace", deadline, None)
        val admitted: Boolean = scope.admitReplacement(ceremony, invocation)
        val handoff = scope.committedReplacement(ceremony, invocation, id)
        val control = scope.replacementControl(handoff, invocation)
        val delivery = scope.replacementDelivery(request, id)
        val consumed: Boolean = scope.consumeReplacementDelivery(delivery, request)
        val metadata = scope.successorCompletion(id, id)
        ()
      }
    """) shouldBe true
  }

  it should "keep capability constructors and the invocation factory inaccessible" in {
    typeChecks("""
      new spoonbill.auth.BrowserReplacementRequest("binding", None, None)
    """) shouldBe false
    typeChecks("""
      new spoonbill.auth.BrowserReplacementCeremony(null, null, null, "replace", null, None)
    """) shouldBe false
    typeChecks("""
      new spoonbill.auth.BrowserReplacementInvocation(null, null, null, null, null)
    """) shouldBe false
    typeChecks("""
      new spoonbill.auth.CommittedReplacement(null, null, null, null, null, null, null, null)
    """) shouldBe false
    typeChecks("""
      new spoonbill.auth.BrowserReplacementControl(null)
    """) shouldBe false
    typeChecks("""
      new spoonbill.auth.BrowserReplacementDelivery(null, null, null)
    """) shouldBe false
    typeChecks("""
      new spoonbill.auth.VerifiedSuccessorCompletion(null, null)
    """) shouldBe false
    typeChecks("""
      new spoonbill.auth.BrowserCapabilityScope[String].replacementInvocation(null, null)
    """) shouldBe false
    typeChecks("""
      spoonbill.auth.BrowserReplacementInvocation.capture(null, null)
    """) shouldBe false
  }

  it should "keep provenance keys and use flags inaccessible" in {
    typeChecks("""
      def key(value: spoonbill.auth.BrowserReplacementInvocation): AnyRef = value.invocationKey
    """) shouldBe false
    typeChecks("""
      def issuer(value: spoonbill.auth.CommittedReplacement): AnyRef = value.issuer
    """) shouldBe false
    typeChecks("""
      def key(value: spoonbill.auth.BrowserReplacementRequest): AnyRef = value.requestKey
    """) shouldBe false
    typeChecks("""
      def consume(value: spoonbill.auth.BrowserReplacementDelivery): Boolean = value.consumed.compareAndSet(false, true)
    """) shouldBe false
  }

  it should "keep ordinary and replacement ceremonies, receipts and provider support distinct" in {
    typeChecks("""
      def ordinary(value: spoonbill.auth.BrowserReplacementCeremony): spoonbill.auth.BrowserCeremony = value
    """) shouldBe false
    typeChecks("""
      def replacement(value: spoonbill.auth.CommittedAuthentication): spoonbill.auth.CommittedReplacement = value
    """) shouldBe false
    typeChecks("""
      def supported[F[_], G[_], P](value: spoonbill.auth.TransactionalBrowserPersistence[F, G, P]):
        spoonbill.auth.ReplacementBrowserPersistence[F, G, P] = value
    """) shouldBe false
  }
}
