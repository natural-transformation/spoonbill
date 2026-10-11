package spoonbill.auth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.Qsid
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.transaction.TransactionFailure

/**
 * Provider-independent laws, compiled into both provider test projects.
 * Fixtures use synthetic host sessions for subject "subject", policy version 1.
 * Actual host mutation and rollback are implemented by each provider fixture.
 */
object BrowserPersistenceConformance {
  val start: Instant  = Instant.parse("2026-10-11T00:00:00Z")
  val binding: String = "b" * 43
  trait Fixture extends AutoCloseable {
    def persistence: BrowserPersistence[Future, String]
    def clock: AtomicReference[Instant]
    def prepare(
      ceremony: BrowserCeremony,
      fail: Boolean = false
    ): Future[Either[TransactionFailure, CommittedAuthentication]]
    def committedSessions: Int
  }
}

trait BrowserPersistenceConformance extends AnyFlatSpec with Matchers {
  import BrowserPersistenceConformance.*
  protected def fixture(): Fixture
  private def await[A](value: Future[A]): A = Await.result(value, 10.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected rejection: $error"), identity)
  private def owner(id: Long): ConnectionId = ConnectionId.fromUuid(new UUID(0L, id))
  private val qsid                          = Qsid("device", "view")
  private def open(db: Fixture): BrowserView[String] = {
    val captured = accepted(await(db.persistence.capture(binding, None, activate = false)))
    accepted(await(db.persistence.claim(captured, qsid, owner(1), existingOnly = false)))
  }

  "A browser persistence provider" should "prepare once, deliver only to the bound browser, and activate on a fresh handshake" in
    Using.resource(fixture()) { db =>
      val view     = open(db)
      val ceremony = accepted(await(db.persistence.begin(view, "login")))
      val receipt  = accepted(await(db.prepare(ceremony)))
      db.persistence.accepts(receipt) shouldBe true
      db.committedSessions shouldBe 1
      await(db.persistence.deliver(receipt.completionId, "x" * 43)) shouldBe None
      val credential = await(db.persistence.deliver(receipt.completionId, binding)).get
      accepted(await(db.persistence.capture(binding, Some(credential.value), activate = false))).principal shouldBe None
      accepted(await(db.persistence.authorize(view))).principal shouldBe None
      val active = accepted(await(db.persistence.capture(binding, Some(credential.value), activate = true)))
      active.principal.map(_.subject) shouldBe Some("subject")
      accepted(await(db.persistence.capture(binding, Some(credential.value), activate = true)))
        .sameAuthority(active) shouldBe true
      await(db.persistence.authorize(view)).isLeft shouldBe true
      await(db.persistence.capture(binding, None, activate = false)).isLeft shouldBe true
      await(db.persistence.deliver(receipt.completionId, binding)) shouldBe None
      await(db.prepare(ceremony)).isLeft shouldBe true
      db.committedSessions shouldBe 1
    }

  it should "resume the original challenge through a new owner without refreshing its deadline" in Using.resource(
    fixture()
  ) { db =>
    val view      = open(db)
    val ceremony  = accepted(await(db.persistence.begin(view, "login")))
    val challenge = BrowserChallenge("subject", 7L, 1L, "challenge")
    val bound     = accepted(await(db.persistence.bindChallenge(ceremony, challenge)))
    val next      = accepted(await(db.persistence.claim(view.identity, qsid, owner(2), existingOnly = true)))
    val resumed   = accepted(await(db.persistence.resume(next, ceremony.id, "login")))
    resumed.challenge shouldBe Some(challenge)
    resumed.expiresAt shouldBe bound.expiresAt
    await(db.persistence.resume(next, ceremony.id, "different-purpose")).isLeft shouldBe true
    await(db.persistence.release(view))
    await(db.persistence.authorize(next)).isRight shouldBe true
    await(db.prepare(bound)).isLeft shouldBe true
    await(db.prepare(resumed)).isRight shouldBe true
    db.committedSessions shouldBe 1
  }

  it should "rollback every staged host mutation and forbid same-attempt proof replay" in Using.resource(fixture()) {
    db =>
      val view     = open(db)
      val ceremony = accepted(await(db.persistence.begin(view, "login")))
      await(db.prepare(ceremony, fail = true)).isLeft shouldBe true
      db.committedSessions shouldBe 0
      await(db.prepare(ceremony)).isLeft shouldBe true
      await(db.persistence.resume(view, ceremony.id, "login")).isLeft shouldBe true
      accepted(await(db.persistence.recover(view, ceremony.id))) shouldBe BrowserRecovery.NotCommitted
      await(db.prepare(ceremony)).isLeft shouldBe true
      db.committedSessions shouldBe 0
  }

  it should "fence preparation with logout even when the ceremony was previously authorized" in Using.resource(
    fixture()
  ) { db =>
    val view     = open(db)
    val ceremony = accepted(await(db.persistence.begin(view, "login")))
    accepted(await(db.persistence.logout(binding, None)))
    await(db.prepare(ceremony)).isLeft shouldBe true
    db.committedSessions shouldBe 0
    await(db.persistence.authorize(view)).isLeft shouldBe true
  }

  it should "fence already prepared delivery and cookies on logout" in Using.resource(fixture()) { db =>
    val view       = open(db)
    val receipt    = accepted(await(db.prepare(accepted(await(db.persistence.begin(view, "login"))))))
    val credential = await(db.persistence.deliver(receipt.completionId, binding)).get
    accepted(await(db.persistence.logout(binding, None)))
    await(db.persistence.deliver(receipt.completionId, binding)) shouldBe None
    await(db.persistence.capture(binding, Some(credential.value), activate = true)).isLeft shouldBe true
    db.committedSessions shouldBe 1
  }

  it should "recover delivery without rerunning the host mutation" in Using.resource(fixture()) { db =>
    val view     = open(db)
    val ceremony = accepted(await(db.persistence.begin(view, "login")))
    val receipt  = accepted(await(db.prepare(ceremony)))
    val recovered = accepted(await(db.persistence.recover(view, ceremony.id))) match {
      case BrowserRecovery.Committed(value) => value
      case _                                => fail("Expected a committed preparation")
    }
    recovered.completionId shouldBe receipt.completionId
    db.persistence.accepts(recovered) shouldBe true
    await(db.persistence.deliver(recovered.completionId, binding)).isDefined shouldBe true
    db.committedSessions shouldBe 1
  }

  it should "retain expiry across a backward clock observation within the same issuer" in Using.resource(fixture()) {
    db =>
      val view     = open(db)
      val ceremony = accepted(await(db.persistence.begin(view, "login")))
      db.clock.set(ceremony.expiresAt)
      await(db.persistence.resume(view, ceremony.id, "login")).isLeft shouldBe true
      db.clock.set(start)
      await(db.prepare(ceremony)).isLeft shouldBe true
      db.committedSessions shouldBe 0
  }

  it should "refuse unknown resume views without allocating an owned view" in Using.resource(fixture()) { db =>
    val identity = accepted(await(db.persistence.capture(binding, None, activate = false)))
    await(db.persistence.claim(identity, Qsid("device", "missing"), owner(1), existingOnly = true)).isLeft shouldBe true
    accepted(await(db.persistence.claim(identity, qsid, owner(2), existingOnly = false)))
  }
}
