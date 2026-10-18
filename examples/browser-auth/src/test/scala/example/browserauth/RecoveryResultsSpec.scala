package example.browserauth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.action.{StateUpdate, UiOutcome}
import spoonbill.auth.MemoryReplacementTestAccess
import spoonbill.effect.Effect
import spoonbill.security.jdbc.JdbcCredentialVault

class RecoveryResultsSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext            = ExecutionContext.global
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private val start                         = Instant.parse("2026-10-16T00:00:00Z")
  private class Fixture {
    val clock            = new AtomicReference(start)
    val decryptions      = new AtomicInteger()
    private val delegate = JdbcCredentialVault.aesGcm(Array.fill[Byte](32)(7))
    val results = new RecoveryResults(
      new JdbcCredentialVault {
        def seal(plaintext: Array[Byte], associated: Array[Byte]): Array[Byte] = delegate.seal(plaintext, associated)
        def open(ciphertext: Array[Byte], associated: Array[Byte]): Array[Byte] = {
          decryptions.incrementAndGet()
          delegate.open(ciphertext, associated)
        }
      },
      () => clock.get()
    )
    val session                        = UUID.randomUUID()
    val view                           = MemoryReplacementTestAccess.view(Principal(UUID.randomUUID(), "synthetic"), session)
    val owner                          = await(MemoryReplacementTestAccess.context(view))
    def record: RecoveryResults.Record = results.create(UUID.randomUUID(), session, start).record
  }

  "Recovery result presentation" should "require the original private invocation before the first decryption" in {
    val f           = new Fixture
    val copied      = await(MemoryReplacementTestAccess.context(f.view, Some(f.owner.context)))
    val foreignView = MemoryReplacementTestAccess.view(Principal(UUID.randomUUID(), "other"), UUID.randomUUID())
    val foreign     = await(MemoryReplacementTestAccess.context(foreignView))
    try {
      copied.context.invocationId shouldBe f.owner.context.invocationId
      copied.context.connectionId shouldBe f.owner.context.connectionId
      val consumed = RecoveryResults.consumed(f.record, f.owner.invocation)
      f.results.present(consumed, foreign.context) shouldBe a[StateUpdate.Modify[?]]
      f.results.present(consumed, copied.context) shouldBe a[StateUpdate.Modify[?]]
      consumed.claim(copied.invocation, start) shouldBe None
      f.decryptions.get() shouldBe 0
      f.results.present(consumed, f.owner.context) shouldBe a[UiOutcome.PresentSensitive[?]]
      f.decryptions.get() shouldBe 1
      f.results.present(consumed, f.owner.context) shouldBe a[StateUpdate.Modify[?]]
      consumed.claim(f.owner.invocation, start) shouldBe None
      f.decryptions.get() shouldBe 1
    } finally { f.owner.close(); copied.close(); foreign.close() }
  }

  it should "reject a result issued for a different successor session" in {
    val f = new Fixture
    try {
      intercept[IllegalArgumentException] {
        RecoveryResults.consumed(f.record.copy(sessionId = UUID.randomUUID()), f.owner.invocation)
      }
      f.decryptions.get() shouldBe 0
    } finally f.owner.close()
  }

  it should "erase expired holders before decryption and never revive them when the clock moves backward" in {
    val f = new Fixture
    try {
      val record         = f.record
      val expired        = RecoveryResults.consumed(record, f.owner.invocation)
      val notYetObserved = RecoveryResults.consumed(f.record, f.owner.invocation)
      f.clock.set(record.expiresAt)
      f.results.present(expired, f.owner.context) shouldBe a[StateUpdate.Modify[?]]
      f.clock.set(start)
      f.results.present(expired, f.owner.context) shouldBe a[StateUpdate.Modify[?]]
      f.results.present(notYetObserved, f.owner.context) shouldBe a[StateUpdate.Modify[?]]
      expired.claim(f.owner.invocation, start) shouldBe None
      notYetObserved.claim(f.owner.invocation, start) shouldBe None
      f.decryptions.get() shouldBe 0
    } finally f.owner.close()
  }
}
