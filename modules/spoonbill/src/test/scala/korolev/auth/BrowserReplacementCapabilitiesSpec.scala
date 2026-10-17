package spoonbill.auth

import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.Qsid
import spoonbill.action.InvocationBinding
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}

class BrowserReplacementCapabilitiesSpec extends AnyFlatSpec with Matchers {
  private val expires               = Instant.parse("2026-10-16T12:00:00Z")
  private def id(value: Long): UUID = new UUID(0L, value)
  private val connection            = ConnectionId.fromUuid(id(1))

  private class Fixture {
    val issuer = new BrowserCapabilityScope[String]
    val identity = issuer.identity(
      "binding-cookie",
      Some("credential-cookie"),
      1L,
      Some(HostPrincipal("display", "subject", 1L, 1L, expires)),
      Some(id(2))
    )
    val view       = issuer.view(identity, Qsid("device", "view"), connection, id(3), 1L)
    val binding    = new InvocationBinding(InvocationId.fromUuid(id(4)), connection)
    val invocation = issuer.replacementInvocation(view, binding)
    def ceremony: BrowserReplacementCeremony =
      issuer.replacementCeremony(view, CeremonyId(id(5)), "replace-factor", expires, None)
    def handoff: CommittedReplacement = {
      val attempt = ceremony
      issuer.admitReplacement(attempt, invocation) shouldBe true
      issuer.committedReplacement(attempt, invocation, id(6))
    }
  }

  "Replacement capabilities" should "reject anonymous, foreign and wrong-connection invocation construction" in {
    val f             = new Fixture
    val anonymous     = f.issuer.identity("binding", None, 1L, None)
    val anonymousView = f.issuer.view(anonymous, Qsid("device", "view"), connection, id(3), 1L)
    intercept[IllegalArgumentException] {
      f.issuer.replacementInvocation(anonymousView, f.binding)
    }
    intercept[IllegalArgumentException] {
      f.issuer.replacementCeremony(anonymousView, CeremonyId(id(5)), "replace", expires, None)
    }
    intercept[IllegalArgumentException] {
      new BrowserCapabilityScope[String].replacementInvocation(f.view, f.binding)
    }
    intercept[IllegalArgumentException] {
      f.issuer.replacementInvocation(
        f.view,
        new InvocationBinding(InvocationId.fromUuid(id(4)), ConnectionId.fromUuid(id(9)))
      )
    }
  }

  it should "reject mismatched fences without consuming the original ceremony" in {
    val f               = new Fixture
    val attempt         = f.ceremony
    val newerView       = f.issuer.view(f.identity, f.view.qsid, connection, id(3), 2L)
    val newerInvocation = f.issuer.replacementInvocation(newerView, f.binding)
    f.issuer.admitReplacement(attempt, newerInvocation) shouldBe false
    f.issuer.admitReplacement(attempt, new Fixture().invocation) shouldBe false
    f.issuer.admitReplacement(attempt, f.invocation) shouldBe true
    f.issuer.admitReplacement(attempt, f.invocation) shouldBe false
  }

  it should "reject changed authority even for the same physical owner" in {
    val f           = new Fixture
    val attempt     = f.ceremony
    val changed     = f.issuer.identity("binding-cookie", Some("another-credential"), 2L, f.identity.host, Some(id(7)))
    val changedView = f.issuer.view(changed, f.view.qsid, connection, id(3), 1L)
    f.issuer.admitReplacement(attempt, f.issuer.replacementInvocation(changedView, f.binding)) shouldBe false
    f.issuer.admitReplacement(attempt, f.invocation) shouldBe true
  }

  it should "bind completion to the actual invocation rather than its public IDs" in {
    val f                   = new Fixture
    val handoff             = f.handoff
    val copiedIds           = new InvocationBinding(f.binding.invocationId, f.binding.connectionId)
    val differentInvocation = f.issuer.replacementInvocation(f.view, copiedIds)
    f.issuer.replacementControl(handoff, differentInvocation) shouldBe None
    val repeatedCapture = BrowserReplacementInvocation.capture(f.view, f.binding)
    f.issuer.replacementControl(handoff, repeatedCapture).map(_.completionId) shouldBe Some(id(6))
    f.issuer.replacementControl(handoff, f.invocation) shouldBe None
  }

  it should "refuse a handoff before local admission and reject foreign handoffs" in {
    val f = new Fixture
    intercept[IllegalArgumentException] {
      f.issuer.committedReplacement(f.ceremony, f.invocation, id(6))
    }
    val foreign = new Fixture
    f.issuer.owns(foreign.handoff) shouldBe false
    f.issuer.replacementControl(foreign.handoff, f.invocation) shouldBe None
  }

  it should "retain the preparation invocation when promoting the admitted ceremony" in {
    val f       = new Fixture
    val attempt = f.ceremony
    f.issuer.admitReplacement(attempt, f.invocation) shouldBe true
    val other =
      f.issuer.replacementInvocation(f.view, new InvocationBinding(f.binding.invocationId, f.binding.connectionId))
    intercept[IllegalArgumentException] {
      f.issuer.committedReplacement(attempt, other, id(6))
    }
    val handoff = f.issuer.committedReplacement(attempt, f.invocation, id(6))
    f.issuer.replacementControl(handoff, f.invocation).map(_.completionId) shouldBe Some(id(6))
  }

  it should "bind recovered delivery to one exact request without consuming it for another request" in {
    val f       = new Fixture
    val request = new BrowserReplacementRequest("binding-cookie", Some("credential-cookie"), Some(CeremonyId(id(5))))
    val sameCookies =
      new BrowserReplacementRequest("binding-cookie", Some("credential-cookie"), Some(CeremonyId(id(5))))
    val delivery = f.issuer.replacementDelivery(request, id(6))
    f.issuer.consumeReplacementDelivery(delivery, sameCookies) shouldBe false
    new BrowserCapabilityScope[String].consumeReplacementDelivery(delivery, request) shouldBe false
    f.issuer.consumeReplacementDelivery(delivery, request) shouldBe true
    f.issuer.consumeReplacementDelivery(delivery, request) shouldBe false
  }

  it should "invalidate replacement capabilities when their issuer closes" in {
    val f        = new Fixture
    val ceremony = f.ceremony
    val handoff  = f.handoff
    val request  = new BrowserReplacementRequest("binding-cookie", None, None)
    val delivery = f.issuer.replacementDelivery(request, id(6))
    f.issuer.close()
    f.issuer.owns(ceremony) shouldBe false
    f.issuer.owns(f.invocation) shouldBe false
    f.issuer.owns(handoff) shouldBe false
    f.issuer.owns(delivery) shouldBe false
    f.issuer.admitReplacement(ceremony, f.invocation) shouldBe false
    f.issuer.replacementControl(handoff, f.invocation) shouldBe None
    f.issuer.consumeReplacementDelivery(delivery, request) shouldBe false
  }

  it should "keep capabilities redacted and free of structural serialization and retained request objects" in {
    val f                   = new Fixture
    val handoff             = f.handoff
    val request             = new BrowserReplacementRequest("binding-cookie", Some("credential-cookie"), None)
    val delivery            = f.issuer.replacementDelivery(request, id(6))
    val control             = f.issuer.replacementControl(handoff, f.invocation).get
    val metadata            = f.issuer.successorCompletion(id(20), id(21))
    val values: Seq[AnyRef] = Seq(f.ceremony, f.invocation, handoff, request, delivery, control, metadata)
    values.foreach { value =>
      value.isInstanceOf[Product] shouldBe false
      value.isInstanceOf[java.io.Serializable] shouldBe false
      value.toString should endWith("(<redacted>)")
    }
    Seq(f.invocation, handoff, delivery, control, metadata).foreach { value =>
      val retainedTypes = value.getClass.getDeclaredFields.map(_.getType).toSet
      retainedTypes should not contain classOf[BrowserView[?]]
      retainedTypes should not contain classOf[InvocationBinding]
      retainedTypes should not contain classOf[BrowserReplacementRequest]
    }
    metadata.hostCompletionId shouldBe id(20)
    metadata.successorSessionId shouldBe id(21)
  }
}
