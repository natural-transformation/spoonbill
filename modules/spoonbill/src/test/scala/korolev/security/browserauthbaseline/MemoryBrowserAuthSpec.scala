package spoonbill.browserauthbaseline

import MemoryBrowserAuth.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.security.store.InvocationStatus
import spoonbill.security.transaction.{OperationError, OperationProtocolException, TransactionFailure}

class MemoryBrowserAuthSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext                     = ExecutionContext.global
  private given Effect[Future]                       = new Effect.FutureEffect
  private val start                                  = Instant.parse("2026-10-10T12:00:00Z")
  private val binding                                = "synthetic-binding-cookie"
  private val credential                             = "A" * 43
  private def result[A](value: Future[A]): A         = Await.result(value, 5.seconds)
  private def accepted[E, A](value: Either[E, A]): A = value.fold(e => fail(s"Unexpected rejection: $e"), identity)
  private class Fixture(
    capacity: Int = 64,
    credentialFactory: () => String = () => credential,
    maxCeremoniesPerBinding: Int = 16,
    referencePolicy: Option[ReferencePolicy] = None
  ) {
    val now = new AtomicReference(start)
    val ids = new AtomicLong(10)
    val host = new MemoryBrowserAuth[Future](
      referencePolicy.fold(
        Vector(syntheticAccount("alice", "password"), syntheticAccount("bob", "password", Some("123456")))
      ) { p =>
        val hash = p.proof.hash("password").toVector
        p.accounts.map(a => Account(a.name, hash, a.factor, version = 1L))
      },
      () => now.get(),
      credentialFactory,
      () => new UUID(0, ids.incrementAndGet()),
      capacity,
      maxCeremoniesPerBinding,
      referencePolicy
    )
    def begin(): UUID = accepted(result(host.begin(binding)))
    def login(): (UUID, Principal) = {
      val ceremony = begin()
      result(host.password(ceremony, binding, "alice", "password")) shouldBe Right(Reply.Prepared(ceremony))
      result(host.deliver(ceremony, binding)).isDefined shouldBe true
      ceremony -> accepted(result(host.activate(binding, credential)))
    }
    def challenge(): (UUID, UUID) = {
      val ceremony = begin()
      accepted(result(host.password(ceremony, binding, "bob", "password"))) match {
        case Reply.Challenge(`ceremony`, challenge) => ceremony -> challenge
        case other                                  => fail(s"Expected challenge, got $other")
      }
    }
  }

  "The v3 memory host baseline" should "commit password preparation atomically and activate only on a fresh handshake" in {
    val f        = new Fixture
    val ceremony = f.begin()
    result(f.host.password(ceremony, binding, "alice", "password")) shouldBe Right(Reply.Prepared(ceremony))
    result(f.host.protectedPage(binding, credential)).isLeft shouldBe true
    val prepared = result(f.host.counts)
    prepared.sessions shouldBe 1
    prepared.completions shouldBe 1
    prepared.audits shouldBe 1
    prepared.transactions shouldBe 1
    result(f.host.deliver(ceremony, "wrong-binding")) shouldBe None
    result(f.host.deliver(ceremony, binding)).isDefined shouldBe true
    val principal = accepted(result(f.host.activate(binding, credential)))
    result(f.host.protectedPage(binding, credential)) shouldBe Right(principal)
    result(f.host.activate(binding, credential)) shouldBe Right(principal)
    result(f.host.deliver(ceremony, binding)) shouldBe None
  }

  it should "apply the executable shared proof population and distinct challenge delivery and session deadlines" in {
    val p                     = ReferencePolicy.Default
    val challenged            = new Fixture(referencePolicy = Some(p))
    val (ceremony, challenge) = challenged.challenge()
    challenged.now.set(start.plusSeconds(p.challengeSeconds))
    result(challenged.host.factor(ceremony, binding, challenge, "123456")) shouldBe Left(Failure.Expired)
    val pending = new Fixture(referencePolicy = Some(p))
    val id      = pending.begin()
    result(pending.host.password(id, binding, "account-1000", "password")) match {
      case Right(Reply.Challenge(_, factor)) =>
        result(pending.host.factor(id, binding, factor, "123456")) shouldBe Right(Reply.Prepared(id))
      case other => fail(s"Expected seeded account challenge: $other")
    }
    pending.now.set(start.plusSeconds(p.deliverySeconds))
    result(pending.host.deliver(id, binding)) shouldBe None
    val active         = new Fixture(referencePolicy = Some(p))
    val (_, principal) = active.login()
    val authority      = accepted(result(active.host.actionAuthority(principal)))
    active.now.set(start.plusSeconds(p.operationAuthoritySeconds))
    result(active.host.protectedAction(principal, authority)) shouldBe Left(
      TransactionFailure.Rejected(OperationError.Expired)
    )
    active.now.set(start.plusSeconds(p.sessionSeconds - 1))
    result(active.host.protectedPage(binding, credential)).isRight shouldBe true
    active.now.set(start.plusSeconds(p.sessionSeconds))
    result(active.host.protectedPage(binding, credential)).isLeft shouldBe true
  }

  it should "roll back preparation when a token callback crosses the profile challenge deadline" in {
    val p = ReferencePolicy.Default
    lazy val f: Fixture = new Fixture(
      referencePolicy = Some(p),
      credentialFactory = () => {
        f.now.set(start.plusSeconds(p.challengeSeconds))
        credential
      }
    )
    val (ceremony, challenge) = f.challenge()
    result(f.host.factor(ceremony, binding, challenge, "123456")) shouldBe
      Left(Failure.Settlement(TransactionFailure.Rejected(OperationError.HostDenied)))
    val counts = result(f.host.counts)
    counts.sessions shouldBe 0
    counts.completions shouldBe 0
    counts.retainedDeliveryMaterials shouldBe 0
    counts.audits shouldBe 0
    counts.rollbacks shouldBe 1L
  }

  it should "bound shared browser and account-tuple proof windows while allowing fresh browser scopes" in {
    val p        = ReferencePolicy.Default
    val f        = new Fixture(referencePolicy = Some(p))
    val ceremony = f.begin()
    (1 to p.proofAttempts).foreach { i =>
      result(f.host.password(ceremony, binding, if (i % 2 == 0) "alice" else "bob", "wrong")) shouldBe Left(
        Failure.Denied
      )
    }
    result(f.host.password(ceremony, binding, "alice", "password")) shouldBe Left(Failure.Throttled)
    val fresh = accepted(result(f.host.begin("fresh-browser")))
    result(f.host.password(fresh, "fresh-browser", "alice", "password")) shouldBe Right(Reply.Prepared(fresh))
  }

  it should "bind an immutable factor challenge to its subject and original ceremony" in {
    val tokens             = new AtomicLong()
    val f                  = new Fixture(credentialFactory = () => (if (tokens.incrementAndGet() == 1) "A" else "B") * 43)
    val (first, challenge) = f.challenge()
    result(f.host.password(first, binding, "alice", "password")) shouldBe Left(Failure.Used)
    val (second, secondChallenge) = f.challenge()
    result(f.host.factor(second, binding, challenge, "123456")) shouldBe Left(Failure.Denied)
    result(f.host.counts).transactions shouldBe 0
    result(f.host.factor(second, binding, secondChallenge, "123456")) shouldBe Right(Reply.Prepared(second))
    result(f.host.factor(first, binding, challenge, "123456")) shouldBe Right(Reply.Prepared(first))
    result(f.host.factor(first, binding, challenge, "123456")) shouldBe Left(Failure.Used)
    result(f.host.counts).sessions shouldBe 2
  }

  it should "allow an invalid factor to retry but admit the successfully verified attempt only once" in {
    val f                     = new Fixture
    val (ceremony, challenge) = f.challenge()
    result(f.host.factor(ceremony, binding, challenge, "wrong")) shouldBe Left(Failure.Denied)
    result(f.host.counts).transactions shouldBe 0
    result(f.host.factor(ceremony, binding, challenge, "123456")) shouldBe Right(Reply.Prepared(ceremony))
    result(f.host.factor(ceremony, binding, challenge, "123456")) shouldBe Left(Failure.Used)
    val counts = result(f.host.counts)
    counts.transactions shouldBe 1
    counts.sessions shouldBe 1
    counts.audits shouldBe 1
  }

  it should "apply one bounded password-and-factor attempt window before factor verification" in {
    val f                     = new Fixture
    val (ceremony, challenge) = f.challenge()
    (1 to 7).foreach { _ =>
      result(f.host.factor(ceremony, binding, challenge, "wrong")) shouldBe Left(Failure.Denied)
    }
    result(f.host.factor(ceremony, binding, challenge, "123456")) shouldBe Left(Failure.Throttled)
    result(f.host.counts).transactions shouldBe 0
    f.now.set(start.plusSeconds(60))
    result(f.host.factor(ceremony, binding, challenge, "123456")) shouldBe Right(Reply.Prepared(ceremony))
    result(f.host.counts).sessions shouldBe 1
  }

  it should "burn a successfully verified factor admission when final preparation rolls back" in {
    val f                     = new Fixture
    val (ceremony, challenge) = f.challenge()
    f.host.failNextCommit()
    result(f.host.factor(ceremony, binding, challenge, "123456")) shouldBe
      Left(Failure.Settlement(TransactionFailure.RolledBack))
    result(f.host.factor(ceremony, binding, challenge, "123456")) shouldBe Left(Failure.Used)
    result(f.host.recover(ceremony, binding)) shouldBe Right(Recovery.NotCommitted)
    val counts = result(f.host.counts)
    counts.sessions shouldBe 0
    counts.completions shouldBe 0
    counts.audits shouldBe 0
    counts.rollbacks shouldBe 1
  }

  it should "invalidate challenge evidence after a policy change or original expiry" in {
    val f                     = new Fixture
    val (ceremony, challenge) = f.challenge()
    result(f.host.changeAccount("bob", enabled = true))
    result(f.host.factor(ceremony, binding, challenge, "123456")).isLeft shouldBe true
    result(f.host.counts).sessions shouldBe 0
    val g                 = new Fixture
    val (expired, factor) = g.challenge()
    g.now.set(start.plusSeconds(120))
    result(g.host.factor(expired, binding, factor, "123456")) shouldBe Left(Failure.Expired)
    g.now.set(start)
    result(g.host.factor(expired, binding, factor, "123456")) shouldBe Left(Failure.Expired)
  }

  it should "discard all preparation writes on rollback and burn the admitted attempt" in {
    val f        = new Fixture
    val ceremony = f.begin()
    f.host.failNextCommit()
    result(f.host.password(ceremony, binding, "alice", "password")) shouldBe Left(
      Failure.Settlement(TransactionFailure.RolledBack)
    )
    val counts = result(f.host.counts)
    counts.sessions shouldBe 0
    counts.completions shouldBe 0
    counts.audits shouldBe 0
    counts.rollbacks shouldBe 1
    result(f.host.password(ceremony, binding, "alice", "password")) shouldBe Left(Failure.Used)
    result(f.host.recover(ceremony, binding)) shouldBe Right(Recovery.NotCommitted)
  }

  it should "recover committed delivery after acknowledgement loss without consuming proof twice" in {
    val f        = new Fixture
    val ceremony = f.begin()
    f.host.loseNextCommitAcknowledgement()
    result(f.host.password(ceremony, binding, "alice", "password")) shouldBe Left(
      Failure.Settlement(TransactionFailure.CommitUnknown)
    )
    result(f.host.recover(ceremony, "wrong-binding")).isLeft shouldBe true
    result(f.host.recover(ceremony, binding)) shouldBe Right(Recovery.Committed(ceremony))
    result(f.host.deliver(ceremony, binding)).isDefined shouldBe true
    result(f.host.password(ceremony, binding, "alice", "password")) shouldBe Left(Failure.Used)
    val counts = result(f.host.counts)
    counts.sessions shouldBe 1
    counts.audits shouldBe 1
    counts.proofComputations shouldBe 1
  }

  it should "fence late work when recovery establishes that preparation did not commit" in {
    val f                     = new Fixture
    val (ceremony, challenge) = f.challenge()
    result(f.host.recover(ceremony, binding)) shouldBe Right(Recovery.NotCommitted)
    result(f.host.factor(ceremony, binding, challenge, "123456")) shouldBe Left(Failure.Used)
    result(f.host.counts).sessions shouldBe 0
  }

  it should "execute a protected action once and reconcile only its status" in {
    val f              = new Fixture
    val (_, principal) = f.login()
    val authority      = accepted(result(f.host.actionAuthority(principal)))
    f.host.loseNextCommitAcknowledgement()
    result(f.host.protectedAction(principal, authority)) shouldBe Left(TransactionFailure.CommitUnknown)
    result(f.host.operations.reconcile(authority.reference)).map(_.status) shouldBe Right(InvocationStatus.Committed)
    result(f.host.protectedAction(principal, authority)) shouldBe Left(
      TransactionFailure.Rejected(OperationError.PermitUsed)
    )
    result(f.host.counts).protectedMutations shouldBe 1
  }

  it should "roll back business mutations and audit together and retain proof replay fences" in {
    val f              = new Fixture
    val (_, principal) = f.login()
    val authority      = accepted(result(f.host.actionAuthority(principal)))
    f.host.failNextCommit()
    result(f.host.protectedAction(principal, authority)) shouldBe Left(TransactionFailure.RolledBack)
    result(f.host.operations.reconcile(authority.reference)).map(_.status) shouldBe Right(InvocationStatus.NotCommitted)
    result(f.host.counts).protectedMutations shouldBe 0
    result(f.host.counts).audits shouldBe 1
    result(f.host.protectedAction(principal, authority)) shouldBe Left(
      TransactionFailure.Rejected(OperationError.PermitUsed)
    )
  }

  it should "keep account preference counters independent and roll back each account without advancing either" in {
    val issued     = new AtomicLong()
    val f          = new Fixture(credentialFactory = () => (if (issued.incrementAndGet() == 1) "A" else "B") * 43)
    val (_, alice) = f.login()
    val bobBinding = "bob-browser-binding"
    val ceremony   = accepted(result(f.host.begin(bobBinding)))
    val challenge = accepted(result(f.host.password(ceremony, bobBinding, "bob", "password"))) match {
      case Reply.Challenge(`ceremony`, value) => value
      case other                              => fail(s"Expected Bob's challenge, got $other")
    }
    result(f.host.factor(ceremony, bobBinding, challenge, "123456")) shouldBe Right(Reply.Prepared(ceremony))
    val bob = accepted(result(f.host.activate(bobBinding, "B" * 43)))
    def increment(principal: Principal): Either[TransactionFailure, Int] = {
      val authority = accepted(result(f.host.actionAuthority(principal)))
      result(f.host.protectedAction(principal, authority))
    }
    increment(alice) shouldBe Right(1)
    increment(bob) shouldBe Right(1)
    increment(alice) shouldBe Right(2)
    f.host.failNextCommit()
    increment(alice) shouldBe Left(TransactionFailure.RolledBack)
    result(f.host.counts).protectedMutations shouldBe 3
    increment(bob) shouldBe Right(2)
    f.host.failNextCommit()
    increment(bob) shouldBe Left(TransactionFailure.RolledBack)
    result(f.host.counts).protectedMutations shouldBe 4
    increment(alice) shouldBe Right(3)
    increment(bob) shouldBe Right(3)
    val counts = result(f.host.counts)
    counts.protectedMutations shouldBe 6
    counts.audits shouldBe 8
  }

  it should "revoke captured connections, pending challenges, protected pages and late stale cookies on logout" in {
    val f                    = new Fixture
    val (_, principal)       = f.login()
    val (pending, challenge) = f.challenge()
    val authority            = accepted(result(f.host.actionAuthority(principal)))
    result(f.host.logout(binding)) shouldBe Right(())
    result(f.host.authorize(principal)).isLeft shouldBe true
    result(f.host.protectedPage(binding, credential)).isLeft shouldBe true
    result(f.host.activate(binding, credential)).isLeft shouldBe true
    result(f.host.factor(pending, binding, challenge, "123456")).isLeft shouldBe true
    result(f.host.protectedAction(principal, authority)).isLeft shouldBe true
    result(f.host.counts).protectedMutations shouldBe 0
  }

  it should "let only one competing preparation activate the captured generation" in {
    val credentials = new AtomicLong()
    val f           = new Fixture(credentialFactory = () => (if (credentials.incrementAndGet() == 1) "A" else "B") * 43)
    val a           = f.begin()
    val b           = f.begin()
    result(f.host.password(a, binding, "alice", "password")) shouldBe Right(Reply.Prepared(a))
    result(f.host.password(b, binding, "alice", "password")) shouldBe Right(Reply.Prepared(b))
    result(f.host.activate(binding, "A" * 43)).isRight shouldBe true
    result(f.host.activate(binding, "B" * 43)).isLeft shouldBe true
  }

  it should "bound redelivery, admission and proof work before hashing" in {
    val f        = new Fixture
    val ceremony = f.begin()
    result(f.host.password(ceremony, binding, "alice", "password")) shouldBe Right(Reply.Prepared(ceremony))
    (1 to 3).foreach(_ => result(f.host.deliver(ceremony, binding)).isDefined shouldBe true)
    result(f.host.deliver(ceremony, binding)) shouldBe None
    val g        = new Fixture
    val attempts = g.begin()
    (1 to 8).foreach(_ => result(g.host.password(attempts, binding, "alice", "wrong")) shouldBe Left(Failure.Denied))
    result(g.host.password(attempts, binding, "alice", "password")) shouldBe Left(Failure.Throttled)
    result(g.host.counts).proofComputations shouldBe 8
    val bounded = new Fixture(capacity = 1)
    bounded.begin()
    result(bounded.host.begin(binding)) shouldBe Left(Failure.Capacity)
  }

  it should "discard all prior authority after process-local restart" in {
    val old                   = new Fixture
    val (ceremony, principal) = old.login()
    old.host.close()
    val restarted = new Fixture
    result(restarted.host.recover(ceremony, binding)).isLeft shouldBe true
    result(restarted.host.protectedPage(binding, credential)).isLeft shouldBe true
    result(restarted.host.authorize(principal)).isLeft shouldBe true
    result(restarted.host.activate(binding, credential)).isLeft shouldBe true
    result(restarted.host.counts).sessions shouldBe 0
  }

  for (captured <- Vector(false, true)) {
    it should s"bound retained ceremonies per binding in the ${if (captured) "captured" else "direct"} path without consuming another browser's quota" in {
      val f = new Fixture(capacity = 4, maxCeremoniesPerBinding = 1)
      def begin(value: String): Either[Failure, UUID] =
        if (captured) result(f.host.atomically {
          f.host.beginCapturedLocked(f.host.captureIdentityLocked(value, None))
        })
        else result(f.host.begin(value))
      val ceremony = accepted(begin(binding))
      val challenge = accepted(result(f.host.password(ceremony, binding, "bob", "password"))) match {
        case Reply.Challenge(`ceremony`, value) => value
        case other                              => fail(s"Expected challenge, got $other")
      }
      begin(binding) shouldBe Left(Failure.Capacity)
      accepted(begin("independent-browser"))
      result(f.host.factor(ceremony, binding, challenge, "123456")) shouldBe Right(Reply.Prepared(ceremony))
      begin(binding) shouldBe Left(Failure.Capacity)
      f.now.set(start.plusSeconds(121))
      result(f.host.retireExpiredMaterial()) shouldBe 1
      begin(binding) shouldBe Left(Failure.Capacity)
      accepted(begin("third-browser"))
      accepted(begin("fourth-browser"))
      if (captured) intercept[spoonbill.server.SessionAccessDenied](begin("fifth-browser"))
      else begin("fifth-browser") shouldBe Left(Failure.Capacity)
      result(f.host.counts).completions shouldBe 1
    }
  }

  it should "erase expired delivery plaintext without retiring capacity or replay fences even when time reverses" in {
    val f        = new Fixture(capacity = 1)
    val ceremony = f.begin()
    result(f.host.password(ceremony, binding, "alice", "password")) shouldBe Right(Reply.Prepared(ceremony))
    result(f.host.counts).retainedDeliveryMaterials shouldBe 1
    f.now.set(start.plusSeconds(120))
    result(f.host.retireExpiredMaterial()) shouldBe 1
    val retained = result(f.host.counts)
    retained.retainedDeliveryMaterials shouldBe 0
    retained.sessions shouldBe 1
    retained.completions shouldBe 1
    retained.audits shouldBe 1
    retained.transactions shouldBe 1
    result(f.host.retireExpiredMaterial()) shouldBe 0
    f.now.set(start)
    result(f.host.deliver(ceremony, binding)) shouldBe None
    result(f.host.password(ceremony, binding, "alice", "password")) shouldBe Left(Failure.Expired)
    result(f.host.begin(binding)) shouldBe Left(Failure.Capacity)
    f.host.close()
    result(f.host.retireExpiredMaterial()) shouldBe 0
  }

  it should "clear expiry through ordinary reads and erase revoked or competing material after committed transitions" in {
    val expired = new Fixture
    val attempt = expired.begin()
    result(expired.host.password(attempt, binding, "alice", "password")) shouldBe Right(Reply.Prepared(attempt))
    expired.now.set(start.plusSeconds(120))
    result(expired.host.deliver(attempt, binding)) shouldBe None
    result(expired.host.retireExpiredMaterial()) shouldBe 0
    result(expired.host.counts).retainedDeliveryMaterials shouldBe 0

    val revoked       = new Fixture
    val deniedAttempt = revoked.begin()
    result(revoked.host.password(deniedAttempt, binding, "alice", "password")) shouldBe Right(
      Reply.Prepared(deniedAttempt)
    )
    result(revoked.host.changeAccount("alice", enabled = false))
    result(revoked.host.retireExpiredMaterial()) shouldBe 0
    result(revoked.host.counts).retainedDeliveryMaterials shouldBe 0
    result(revoked.host.deliver(deniedAttempt, binding)) shouldBe None

    val tokens    = new AtomicLong()
    val competing = new Fixture(credentialFactory = () => (if (tokens.incrementAndGet() == 1) "A" else "B") * 43)
    val first     = competing.begin()
    val second    = competing.begin()
    result(competing.host.password(first, binding, "alice", "password")) shouldBe Right(Reply.Prepared(first))
    result(competing.host.password(second, binding, "alice", "password")) shouldBe Right(Reply.Prepared(second))
    result(competing.host.counts).retainedDeliveryMaterials shouldBe 2
    val principal = accepted(result(competing.host.activate(binding, credential)))
    result(competing.host.retireExpiredMaterial()) shouldBe 0
    result(competing.host.counts).retainedDeliveryMaterials shouldBe 0
    result(competing.host.protectedPage(binding, credential)) shouldBe Right(principal)
    result(competing.host.deliver(second, binding)) shouldBe None
    result(competing.host.counts).completions shouldBe 2
  }

  it should "erase delivery material at the redelivery bound without invalidating an issued cookie" in {
    val f        = new Fixture
    val ceremony = f.begin()
    result(f.host.password(ceremony, binding, "alice", "password")) shouldBe Right(Reply.Prepared(ceremony))
    (1 to 3).foreach(_ => result(f.host.deliver(ceremony, binding)).isDefined shouldBe true)
    result(f.host.retireExpiredMaterial()) shouldBe 0
    result(f.host.counts).retainedDeliveryMaterials shouldBe 0
    result(f.host.deliver(ceremony, binding)) shouldBe None
    result(f.host.activate(binding, credential)).isRight shouldBe true
    result(f.host.counts).sessions shouldBe 1
  }

  it should "close retained transaction handles and reject nested host transactions" in {
    val f = new Fixture
    val retained = accepted(result(f.host.executor.transact { tx =>
      tx.requireActive(); tx
    }))
    intercept[OperationProtocolException](retained.requireActive()).error shouldBe OperationError.ScopeClosed
    val nested = result(f.host.executor.transact { _ =>
      // Future is eager in the current v3 Effect. The nested callback cannot enter.
      f.host.executor.transact(_ => fail("Nested transaction body entered"))
    })
    result(accepted(nested)) shouldBe Left(TransactionFailure.Rejected(OperationError.TransactionRequired))
  }

  it should "reject all authority and admission operations after idempotent release" in {
    val f                     = new Fixture
    val (ceremony, principal) = f.login()
    val authority             = accepted(result(f.host.actionAuthority(principal)))
    f.host.close()
    f.host.close()
    intercept[spoonbill.server.SessionAccessDenied](result(f.host.begin(binding)))
    intercept[spoonbill.server.SessionAccessDenied](result(f.host.authorize(principal)))
    intercept[spoonbill.server.SessionAccessDenied](result(f.host.activate(binding, credential)))
    intercept[spoonbill.server.SessionAccessDenied](result(f.host.deliver(ceremony, binding)))
    intercept[spoonbill.server.SessionAccessDenied](result(f.host.recover(ceremony, binding)))
    result(f.host.protectedAction(principal, authority)) shouldBe Left(
      TransactionFailure.Rejected(OperationError.ScopeClosed)
    )
  }

  for (captured <- Vector(false, true)) {
    it should s"preserve retained challenge and recovery fences when ${if (captured) "connection-bound" else "direct"} begin generates a duplicate ID" in {
      val ids = Vector(1L, 2L, 3L, 4L, 3L, 5L, 3L).iterator
      val host = new MemoryBrowserAuth[Future](
        Vector(syntheticAccount("bob", "password", Some("123456"))),
        () => start,
        () => credential,
        () => new UUID(0L, ids.next())
      )
      def begin(): Either[Failure, UUID] =
        if (captured) result(host.atomically {
          host.beginCapturedLocked(host.captureIdentityLocked(binding, None))
        })
        else result(host.begin(binding))
      val ceremony = accepted(begin())
      val challenge = accepted(result(host.password(ceremony, binding, "bob", "password"))) match {
        case Reply.Challenge(`ceremony`, value) => value
        case other                              => fail(s"Expected retained challenge, got $other")
      }
      begin() shouldBe Left(Failure.Denied)
      result(host.factor(ceremony, binding, challenge, "123456")) shouldBe Right(Reply.Prepared(ceremony))
      begin() shouldBe Left(Failure.Denied)
      result(host.recover(ceremony, binding)) shouldBe Right(Recovery.Committed(ceremony))
      result(host.factor(ceremony, binding, challenge, "123456")) shouldBe Left(Failure.Used)
      val counts = result(host.counts)
      counts.sessions shouldBe 1
      counts.completions shouldBe 1
      counts.audits shouldBe 1
      ids.hasNext shouldBe false
    }
  }

  it should "roll back a colliding session ID without replacing existing authority or partially preparing a completion" in {
    val ids    = Vector(1L, 2L, 3L, 4L, 5L, 6L, 7L, 4L).iterator
    val tokens = new AtomicLong()
    val host = new MemoryBrowserAuth[Future](
      Vector(syntheticAccount("alice", "password"), syntheticAccount("bob", "password")),
      () => start,
      () => (if (tokens.incrementAndGet() == 1) "A" else "B") * 43,
      () => new UUID(0L, ids.next())
    )
    val first = accepted(result(host.begin(binding)))
    result(host.password(first, binding, "alice", "password")) shouldBe Right(Reply.Prepared(first))
    val alice      = accepted(result(host.activate(binding, credential)))
    val bobBinding = "other-browser-binding"
    val second     = accepted(result(host.begin(bobBinding)))
    result(host.password(second, bobBinding, "bob", "password")) shouldBe
      Left(Failure.Settlement(TransactionFailure.Rejected(OperationError.HostDenied)))
    result(host.authorize(alice)) shouldBe Right(())
    result(host.protectedPage(binding, credential)) shouldBe Right(alice)
    result(host.deliver(second, bobBinding)) shouldBe None
    result(host.recover(second, bobBinding)) shouldBe Right(Recovery.NotCommitted)
    result(host.password(second, bobBinding, "bob", "password")) shouldBe Left(Failure.Used)
    val counts = result(host.counts)
    counts.sessions shouldBe 1
    counts.completions shouldBe 1
    counts.audits shouldBe 1
    counts.rollbacks shouldBe 1
    tokens.get() shouldBe 1
    ids.hasNext shouldBe false
  }

  it should "reject exhausted account versions without changing policy or existing authority" in {
    val host = new MemoryBrowserAuth[Future](
      Vector(syntheticAccount("alice", "password").copy(version = Long.MaxValue)),
      () => start,
      () => credential
    )
    val ceremony = accepted(result(host.begin(binding)))
    result(host.password(ceremony, binding, "alice", "password")) shouldBe Right(Reply.Prepared(ceremony))
    val principal = accepted(result(host.activate(binding, credential)))
    principal.policyVersion shouldBe Long.MaxValue
    val before = result(host.counts)
    intercept[RuntimeException](result(host.changeAccount("alice", enabled = false)))
    result(host.authorize(principal)) shouldBe Right(())
    result(host.protectedPage(binding, credential)) shouldBe Right(principal)
    result(host.counts) shouldBe before
    val authority = accepted(result(host.actionAuthority(principal)))
    result(host.protectedAction(principal, authority)) shouldBe Right(1)
  }

  for (commit <- Vector(true, false)) {
    it should s"exclude recovery until the held login writer ${if (commit) "commits" else "rolls back"}" in {
      val entered   = new CountDownLatch(1)
      val release   = new CountDownLatch(1)
      val attempted = new CountDownLatch(1)
      val f = new Fixture(credentialFactory = () => {
        entered.countDown()
        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Writer barrier timed out")
        credential
      })
      val ceremony = f.begin()
      if (!commit) f.host.failNextCommit()
      val writer = Future(f.host.password(ceremony, binding, "alice", "password")).flatMap(identity)
      try {
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        val recovery = Future { attempted.countDown(); f.host.recover(ceremony, binding) }.flatMap(identity)
        attempted.await(5, TimeUnit.SECONDS) shouldBe true
        recovery.isCompleted shouldBe false
        release.countDown()
        result(writer) shouldBe (if (commit) Right(Reply.Prepared(ceremony))
                                 else Left(Failure.Settlement(TransactionFailure.RolledBack)))
        result(recovery) shouldBe Right(if (commit) Recovery.Committed(ceremony) else Recovery.NotCommitted)
        result(f.host.counts).sessions shouldBe (if (commit) 1 else 0)
      } finally release.countDown()
    }
  }
}
