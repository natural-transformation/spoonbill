package spoonbill.security

import java.time.Instant
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.locks.ReentrantLock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeChecks
import scala.concurrent.{Await, ExecutionContext, Future, blocking}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.store.InvocationStatus
import spoonbill.security.transaction.*

class OneUseAuthoritySpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private given ExecutionContext = ExecutionContext.global
  private val now = Instant.parse("2026-10-08T12:00:00Z")
  private def id(n: Long): UUID = new UUID(0L, n)
  private val binding = OperationBinding(SubjectId.fromUuid(id(1)), RealmId.fromUuid(id(2)),
    AuthSessionId.fromUuid(id(3)), SecurityGeneration.initial,
    OperationPurpose.fromUuid(id(4)), ResourceScope.fromUuid(id(5)))
  private val digest = RequestDigest.fromBytes(Array.fill[Byte](32)(1)).toOption.getOrElse(fail("digest"))
  private def accepted[E, A](value: Either[E, A]): A = value match {
    case Right(result) => result
    case Left(error) => fail(s"Unexpected rejection: $error")
  }
  private def result[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def await(latch: CountDownLatch): Unit = blocking {
    latch.await(5, TimeUnit.SECONDS) shouldBe true
  }

  private case class State(record: Option[StoredOperation], mutations: Int)
  private class Tx {
    val locked = new AtomicBoolean(false)
    val state = new AtomicReference[Option[State]](None)
    def current: State = state.get().getOrElse(fail("transaction not fenced"))
  }

  /** Deterministic transaction fixture: lock acquisition refreshes its snapshot;
    * the lock is held through simulated commit/rollback, exactly as required of
    * production stores. No database availability is needed by these core tests.
    */
  private class Fixture {
    val clock = new AtomicReference(now)
    val issuer = new OneUseAuthorityScope(() => clock.get(), maxOutstanding = 8)
    val durable = new AtomicReference(State(None, 0))
    val fence = new ReentrantLock()
    val dispatches = new AtomicInteger()
    val calls = new AtomicInteger()
    val loseCommit = new AtomicBoolean(false)
    val failDispatch = new AtomicBoolean(false)
    val lockAttempted = new AtomicReference[Option[CountDownLatch]](None)
    val afterLock = new AtomicReference[() => Unit](() => ())
    val store = new DurableOperationStore[Direct, Tx] {
      def readForDecision(tx: Tx, invocation: OperationInvocation): Option[StoredOperation] = {
        lockAttempted.get().foreach(_.countDown())
        blocking(fence.lock())
        tx.locked.set(true)
        tx.state.set(Some(durable.get()))
        afterLock.get()()
        val record = tx.current.record
        record.foreach { stored =>
          if (stored.operation.invocation != invocation)
            throw new OperationProtocolException(OperationError.InvocationConflict)
        }
        record
      }
      def insertPrepared(tx: Tx, operation: PreparedOperation): Unit = fail("outcome-only path must not prepare")
      def recordOutcome(tx: Tx, operation: PreparedOperation, status: InvocationStatus): Unit = {
        tx.locked.get() shouldBe true
        tx.state.set(Some(tx.current.copy(record = Some(StoredOperation(operation, status)))))
      }
    }
    val executor = new TransactionExecutor[Future, Direct, Tx] {
      val program = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Tx => A): Future[Either[TransactionFailure, A]] = {
        dispatches.incrementAndGet()
        if (failDispatch.getAndSet(false)) Future.successful(Left(TransactionFailure.StorageFailure))
        else Future {
          val tx = new Tx
          try {
            val value = body(tx)
            tx.state.get().foreach(durable.set)
            if (loseCommit.getAndSet(false)) Left(TransactionFailure.CommitUnknown)
            else Right(value)
          } catch {
            case error: OperationProtocolException => Left(TransactionFailure.Rejected(error.error))
            case _: IllegalStateException => Left(TransactionFailure.RolledBack)
          } finally {
            if (tx.locked.get()) fence.unlock()
          }
        }
      }
    }
    val protocol = new OneUseOperationProtocol(executor, store, issuer, () => clock.get())
    def evidence(): VerifiedEvidence = accepted(issuer.verified(binding, digest, now.plusSeconds(60)))
    def authority(): ExecutionAuthority = accepted(issuer.issue(evidence()))
    def mutate(scope: TransactionScope[Tx], time: Instant): String = {
      calls.incrementAndGet()
      val tx = scope.transaction
      tx.state.set(Some(tx.current.copy(mutations = tx.current.mutations + 1)))
      "one-read-result"
    }
  }

  "Issuer-bound one-use authority" should "share one claim across racing aliases and issue each canonical evidence only once" in {
    val fixture = new Fixture
    val evidence = fixture.evidence()
    val authority = accepted(fixture.issuer.issue(evidence))
    fixture.issuer.issue(evidence) shouldBe Left(OperationError.EvidenceUsed)
    val jobs = Vector.fill(16)(Future(fixture.protocol.executeIssued(authority)(fixture.mutate)).flatMap(identity))
    val outcomes = result(Future.sequence(jobs))
    outcomes.count(_ == Right("one-read-result")) shouldBe 1
    outcomes.count(_ == Left(TransactionFailure.Rejected(OperationError.PermitUsed))) shouldBe 15
    fixture.calls.get() shouldBe 1
    fixture.dispatches.get() shouldBe 1
    fixture.durable.get().mutations shouldBe 1
  }

  it should "reject foreign issuers without consuming the original authority or evidence" in {
    val fixture = new Fixture
    val foreign = new OneUseAuthorityScope(() => now)
    val evidence = fixture.evidence()
    foreign.issue(evidence) shouldBe Left(OperationError.ForeignPermit)
    val authority = accepted(fixture.issuer.issue(evidence))
    val other = new OneUseOperationProtocol(fixture.executor, fixture.store, foreign, () => now)
    result(other.executeIssued(authority)(fixture.mutate)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.ForeignPermit))
    fixture.dispatches.get() shouldBe 0
    result(fixture.protocol.executeIssued(authority)(fixture.mutate)) shouldBe Right("one-read-result")
  }

  it should "consume before dispatch even if no transaction starts and never replay an admitted body" in {
    val fixture = new Fixture
    val authority = fixture.authority()
    fixture.failDispatch.set(true)
    result(fixture.protocol.executeIssued(authority)(fixture.mutate)) shouldBe Left(TransactionFailure.StorageFailure)
    result(fixture.protocol.executeIssued(authority)(fixture.mutate)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    fixture.calls.get() shouldBe 0
    fixture.dispatches.get() shouldBe 1
    fixture.durable.get().record shouldBe None
    result(fixture.protocol.reconcile(authority.reference)).map(_.status) shouldBe Right(InvocationStatus.NotCommitted)
  }

  it should "burn a failed host attempt while rolling back host writes" in {
    val fixture = new Fixture
    val authority = fixture.authority()
    result(fixture.protocol.executeIssued(authority) { (scope, time) =>
      fixture.mutate(scope, time)
      throw new IllegalStateException("synthetic rollback")
    }) shouldBe Left(TransactionFailure.RolledBack)
    result(fixture.protocol.executeIssued(authority)(fixture.mutate)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    fixture.calls.get() shouldBe 1
    fixture.durable.get().mutations shouldBe 0
    result(fixture.protocol.reconcile(authority.reference)).map(_.status) shouldBe Right(InvocationStatus.NotCommitted)
  }

  it should "fence a late first claim with a negative outcome committed by recovery" in {
    val fixture = new Fixture
    val authority = fixture.authority()
    result(fixture.protocol.reconcile(authority.reference)).map(_.status) shouldBe Right(InvocationStatus.NotCommitted)
    result(fixture.protocol.executeIssued(authority)(fixture.mutate)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.NotPrepared))
    fixture.calls.get() shouldBe 0
  }

  for (commit <- Vector(true, false)) {
    it should s"wait out an original writer before reconciling its ${if (commit) "commit" else "rollback"}" in {
      val fixture = new Fixture
      val authority = fixture.authority()
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val attempted = new CountDownLatch(1)
      val executed = fixture.protocol.executeIssued(authority) { (scope, time) =>
        val value = fixture.mutate(scope, time)
        entered.countDown()
        await(release)
        if (!commit) throw new IllegalStateException("synthetic rollback")
        value
      }
      try {
        await(entered)
        fixture.lockAttempted.set(Some(attempted))
        val recovered = fixture.protocol.reconcile(authority.reference)
        await(attempted)
        recovered.isCompleted shouldBe false
        release.countDown()
        result(executed) shouldBe (if (commit) Right("one-read-result") else Left(TransactionFailure.RolledBack))
        result(recovered).map(_.status) shouldBe
          Right(if (commit) InvocationStatus.Committed else InvocationStatus.NotCommitted)
        fixture.durable.get().mutations shouldBe (if (commit) 1 else 0)
      } finally release.countDown()
    }
  }

  it should "recheck expiry after database locks and keep expired work unexecuted" in {
    val fixture = new Fixture
    val authority = fixture.authority()
    fixture.afterLock.set(() => fixture.clock.set(now.plusSeconds(60)))
    result(fixture.protocol.executeIssued(authority)(fixture.mutate)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.Expired))
    fixture.calls.get() shouldBe 0
    fixture.durable.get().record shouldBe None
  }

  it should "return no one-read result on commit uncertainty and recover only the committed status" in {
    val fixture = new Fixture
    val authority = fixture.authority()
    fixture.loseCommit.set(true)
    result(fixture.protocol.executeIssued(authority)(fixture.mutate)) shouldBe Left(TransactionFailure.CommitUnknown)
    fixture.durable.get().mutations shouldBe 1
    result(fixture.protocol.reconcile(authority.reference)).map(_.status) shouldBe Right(InvocationStatus.Committed)
    result(fixture.protocol.executeIssued(authority)(fixture.mutate)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.PermitUsed))
  }

  it should "bound outstanding receipts and authorities and reclaim expired or admitted leases" in {
    val time = new AtomicReference(now)
    val scope = new OneUseAuthorityScope(() => time.get(), maxOutstanding = 1)
    val first = accepted(scope.conditional(binding, digest, now.plusSeconds(1)))
    val authority = accepted(scope.issue(first))
    scope.verified(binding, digest, now.plusSeconds(60)) shouldBe Left(OperationError.CapacityExceeded)
    time.set(now.plusSeconds(1))
    val next = accepted(scope.verified(binding, digest, now.plusSeconds(60)))
    next.reference.operation.invocation.invocationId should not be authority.reference.operation.invocation.invocationId
    next.reference.operation.definition.id should not be authority.reference.operation.definition.id
    scope.issue(first) shouldBe Left(OperationError.Expired)
    scope.close()
    scope.issue(next) shouldBe Left(OperationError.ScopeClosed)
    scope.verified(binding, digest, now.plusSeconds(60)) shouldBe Left(OperationError.ScopeClosed)
  }

  it should "deny unadmitted authorities on close without pretending to abort admitted transactions" in {
    val fixture = new Fixture
    val authority = fixture.authority()
    val unused = fixture.authority()
    result(fixture.protocol.executeIssued(authority) { (scope, time) =>
      fixture.issuer.close()
      fixture.mutate(scope, time)
    }) shouldBe Right("one-read-result")
    result(fixture.protocol.executeIssued(unused)(fixture.mutate)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.ScopeClosed))
    fixture.calls.get() shouldBe 1
  }

  it should "expose only redacted metadata references and no restart reconstruction or public capability constructor" in {
    val fixture = new Fixture
    val evidence = fixture.evidence()
    val authority = accepted(fixture.issuer.issue(evidence))
    classOf[java.io.Serializable].isAssignableFrom(authority.getClass) shouldBe false
    classOf[java.io.Serializable].isAssignableFrom(evidence.getClass) shouldBe false
    authority.reference.toString shouldBe "OperationReference(<redacted>)"
    authority.toString shouldBe "ExecutionAuthority(<redacted>)"
    evidence.toString shouldBe "OneUseEvidence(<redacted>)"
    val restarted = new OneUseAuthorityScope(() => now)
    val restartedProtocol = new OneUseOperationProtocol(fixture.executor, fixture.store, restarted, () => now)
    result(restartedProtocol.executeIssued(authority)(fixture.mutate)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.ForeignPermit))
    typeChecks("""
      import spoonbill.security.transaction.*
      def restore(scope: OneUseAuthorityScope, reference: OperationReference) = scope.issue(reference)
    """) shouldBe false
    typeChecks("""
      import spoonbill.security.transaction.*
      new ExecutionAuthority(null, null, null)
    """) shouldBe false
  }

  it should "refuse a second callback entry even when a broken executor retries after rollback" in {
    val fixture = new Fixture
    val authority = fixture.authority()
    val broken = new TransactionExecutor[Future, Direct, Tx] {
      val program = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Tx => A): Future[Either[TransactionFailure, A]] =
        fixture.executor.transact(body).flatMap {
          case Left(TransactionFailure.RolledBack) => fixture.executor.transact(body)
          case result => Future.successful(result)
        }
    }
    val protocol = new OneUseOperationProtocol(broken, fixture.store, fixture.issuer, () => now)
    result(protocol.executeIssued(authority) { (scope, time) =>
      fixture.mutate(scope, time)
      throw new IllegalStateException("must not replay")
    }) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    fixture.calls.get() shouldBe 1
    fixture.durable.get().mutations shouldBe 0
  }
}
