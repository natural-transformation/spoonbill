package spoonbill.internal

import avocet.Id
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.action.InvocationBinding
import spoonbill.auth.*
import spoonbill.effect.{Effect, Queue, Reporter, Stream}
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.server.SessionAccessDenied

class ReplacementOutputSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext            = ExecutionContext.global
  private given Effect[Future]              = Effect.futureEffect
  private given Reporter                    = Reporter.PrintReporter
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  private class Fixture {
    val issuer     = new BrowserCapabilityScope[String]
    val connection = ConnectionId.fromUuid(UUID.randomUUID())
    val binding    = new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), connection)
    val identity = issuer.identity(
      "binding",
      Some("credential"),
      1L,
      Some(HostPrincipal("user", "subject", 1L, 1L, Instant.MAX)),
      Some(UUID.randomUUID())
    )
    val view       = issuer.view(identity, Qsid("device", "view"), connection, UUID.randomUUID(), 1L)
    val invocation = issuer.replacementInvocation(view, binding)
    val ceremony   = issuer.replacementCeremony(view, CeremonyId(UUID.randomUUID()), "replace", Instant.MAX, None)
    issuer.admitReplacement(ceremony, invocation)
    val handoff          = issuer.committedReplacement(ceremony, invocation, UUID.randomUUID())
    val ordinary         = new AtomicBoolean(true)
    val liveOwner        = new AtomicBoolean(true)
    val admissions       = new AtomicInteger()
    val inputClosed      = new AtomicBoolean()
    private val incoming = Queue[Future, String]()
    private val input = new Stream[Future, String] {
      def pull(): Future[Option[String]] = incoming.stream.pull()
      def cancel(): Future[Unit]         = { inputClosed.set(true); incoming.stream.cancel() }
    }
    val frontend = new Frontend[Future](
      input,
      None,
      Some(connection),
      authorize = Some(() => if (ordinary.get()) Future.unit else Future.failed(new SessionAccessDenied)),
      captureSensitiveAuthorization =
        Some(_ => Future.failed(new AssertionError("No sensitive presentation expected"))),
      replacementCompletion = Some { (value, owner) =>
        admissions.incrementAndGet()
        if (!liveOwner.get()) Future.failed(new SessionAccessDenied)
        else
          issuer
            .replacementControl(value, BrowserReplacementInvocation.capture(view, owner))
            .fold(Future.failed[BrowserReplacementControl](new SessionAccessDenied))(Future.successful)
      }
    )
  }

  "Replacement output" should "drop queued application output and admit only its fixed frame after revocation" in {
    val f = new Fixture
    try {
      val pendingBinding = await(f.frontend.newActionBinding())
      val payload        = spoonbill.sensitive.SensitivePayload.textList(Vector("synthetic-pending-secret")).toOption.get
      val disclosure     = pendingBinding.sensitiveScope.get.create(payload, pendingBinding.sensitiveOwner)
      disclosure.hasPayload shouldBe true
      await(f.frontend.focus(Id("0_1")))
      f.ordinary.set(false)
      await(f.frontend.completeReplacement(f.handoff, f.binding))
      disclosure.hasPayload shouldBe false
      f.admissions.get() shouldBe 0
      await(f.frontend.outgoingMessages.pull()) shouldBe Some(s"[18,\"${f.handoff.completionId}\"]")
      f.admissions.get() shouldBe 1
      intercept[SessionAccessDenied](await(f.frontend.authorizeInteraction()))
      intercept[SessionAccessDenied](await(f.frontend.completeReplacement(f.handoff, f.binding)))
    } finally await(f.frontend.close())
  }

  it should "reject the queued fixed frame when owner takeover wins before admission" in {
    val f = new Fixture
    try {
      f.ordinary.set(false)
      await(f.frontend.completeReplacement(f.handoff, f.binding))
      f.liveOwner.set(false)
      intercept[SessionAccessDenied](await(f.frontend.outgoingMessages.pull()))
      f.admissions.get() shouldBe 1
    } finally await(f.frontend.close())
  }

  it should "retire a suppressed replacement observer without issuing a handoff or touching unrelated suppressed actions" in {
    val f = new Fixture
    try {
      await(f.frontend.retireReplacement(f.binding))
      f.inputClosed.get() shouldBe false
      await(f.frontend.authorizeInteraction())
      val pending = await(f.frontend.newActionBinding())
      val disclosure = pending.sensitiveScope.get.create(
        spoonbill.sensitive.SensitivePayload.textList(Vector("synthetic-unsent-secret")).toOption.get,
        pending.sensitiveOwner
      )
      f.binding.replacementAttempted.set(true)
      f.ordinary.set(false) // The original mutation committed, but its observer has no receipt.
      await(f.frontend.retireReplacement(f.binding))
      f.inputClosed.get() shouldBe true
      disclosure.hasPayload shouldBe false
      f.admissions.get() shouldBe 0
    } finally await(f.frontend.close())
  }

  it should "send only a recovery lookup under ordinary current authority and reject it after revocation" in {
    val f = new Fixture
    try {
      await(f.frontend.recoverReplacement(f.ceremony.id))
      await(f.frontend.outgoingMessages.pull()).map(_.replace(" ", "")) shouldBe Some(
        s"[26,\"${f.ceremony.id.value}\"]"
      )
      f.admissions.get() shouldBe 0
      await(f.frontend.recoverReplacement(f.ceremony.id))
      f.ordinary.set(false)
      intercept[SessionAccessDenied](await(f.frontend.outgoingMessages.pull()))
      f.admissions.get() shouldBe 0
    } finally await(f.frontend.close())
  }
}
