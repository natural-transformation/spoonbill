package spoonbill.security

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeChecks
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.store.{GrantDefinition, InvocationStatus}
import spoonbill.security.transaction.*

class OperationTransactionsSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private given ExecutionContext = ExecutionContext.parasitic
  private val now = Instant.parse("2026-10-07T12:00:00Z")
  private def id(n: Long): UUID = new UUID(0L, n)
  private val binding = OperationBinding(
    SubjectId.fromUuid(id(1)), RealmId.fromUuid(id(2)), AuthSessionId.fromUuid(id(3)),
    SecurityGeneration.initial, OperationPurpose.fromUuid(id(4)), ResourceScope.fromUuid(id(5)))
  private val definition = GrantDefinition(OperationAuthorizationId.fromUuid(id(6)), binding,
    now.plusSeconds(60), ReservationPolicy.ConsumeOnAttempt)
  private val digest = RequestDigest.fromBytes(Array.fill[Byte](32)(1)).toOption.getOrElse(fail("digest"))
  private val operation = PreparedOperation(definition,
    OperationInvocation(InvocationId.fromUuid(id(7)), definition.id, binding, digest), PreparationKind.Conditional)

  private case class State(row: Option[StoredOperation], mutations: Int)
  private class Tx(initial: State) {
    val state = new AtomicReference(initial)
  }

  private class Fixture {
    val durable = new AtomicReference(State(None, 0))
    val nextCommit = new AtomicReference[Option[Promise[Either[TransactionFailure, Unit]]]](None)
    val lockCalls = new AtomicInteger()
    val clock = new AtomicReference(now)
    val store = new DurableOperationStore[Direct, Tx] {
      def readForDecision(tx: Tx, invocation: OperationInvocation): Option[StoredOperation] = {
        lockCalls.incrementAndGet()
        val record = tx.state.get().row
        record.foreach { row =>
          if (row.operation.invocation != invocation)
            throw new OperationProtocolException(OperationError.InvocationConflict)
        }
        record
      }
      def insertPrepared(tx: Tx, value: PreparedOperation): Unit = {
        tx.state.set(tx.state.get().copy(row = Some(StoredOperation(value, InvocationStatus.InProgress))))
      }
      def recordOutcome(tx: Tx, value: PreparedOperation, status: InvocationStatus): Unit = {
        Set(InvocationStatus.Committed, InvocationStatus.NotCommitted) should contain(status)
        tx.state.set(tx.state.get().copy(row = Some(StoredOperation(value, status))))
      }
    }
    val executor = new TransactionExecutor[Future, Direct, Tx] {
      val program = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Tx => A): Future[Either[TransactionFailure, A]] = {
        val tx = new Tx(durable.get())
        val completion = nextCommit.getAndSet(None)
          .fold(Future.successful[Either[TransactionFailure, Unit]](Right(())))(_.future)
        try {
          val value = body(tx)
          completion.map {
            case Right(_) => durable.set(tx.state.get()); Right(value)
            // Simulate a commit that happened but whose acknowledgment was lost.
            case Left(TransactionFailure.CommitUnknown) =>
              durable.set(tx.state.get()); Left(TransactionFailure.CommitUnknown)
            case Left(failure) => Left(failure)
          }
        } catch {
          case error: OperationProtocolException => Future.successful(Left(TransactionFailure.Rejected(error.error)))
          case _: IllegalStateException => Future.successful(Left(TransactionFailure.RolledBack))
        }
      }
    }
    val protocol = new OperationProtocol(executor, store, () => clock.get())
    def holdCommit(): Promise[Either[TransactionFailure, Unit]] = {
      val promise = Promise[Either[TransactionFailure, Unit]]()
      nextCommit.set(Some(promise))
      promise
    }
    def prepare(): Future[Either[TransactionFailure, Prepared[String]]] =
      protocol.prepare(scope => scope.stage(operation)(_ => "prepared context"))
    def permit(): ExecutionPermit = result(prepare()) match {
      case Right(Prepared.Ready(_, permit)) => permit
      case other => fail(s"Expected permit, got $other")
    }
  }

  private def result[A](future: Future[A]): A = Await.result(future, 3.seconds)

  "Operation transactions" should "publish a permit only after acknowledged outer commit and close the callback scope first" in {
    val fixture = new Fixture
    val commit = fixture.holdCommit()
    val captured = new AtomicReference[Option[PreparationScope[Direct, Tx]]](None)
    val prepared = fixture.protocol.prepare { scope =>
      captured.set(Some(scope))
      scope.stage(operation) { _ =>
        fixture.lockCalls.get() shouldBe 1
        "context"
      }
    }
    prepared.isCompleted shouldBe false
    fixture.durable.get().row shouldBe None
    val scope = captured.get().getOrElse(fail("scope missing"))
    intercept[OperationProtocolException](scope.transaction).error shouldBe OperationError.ScopeClosed
    intercept[OperationProtocolException](scope.stage(operation)(_ => ())).error shouldBe OperationError.ScopeClosed
    commit.success(Right(()))
    result(prepared) match {
      case Right(Prepared.Ready("context", _)) => succeed
      case other => fail(s"Expected acknowledged preparation, got $other")
    }
  }

  it should "return no capability after an ambiguous preparation commit and never replay preparation" in {
    val fixture = new Fixture
    val commit = fixture.holdCommit()
    val prepared = fixture.prepare()
    commit.success(Left(TransactionFailure.CommitUnknown))
    result(prepared) shouldBe Left(TransactionFailure.CommitUnknown)
    result(fixture.protocol.prepare(scope => scope.stage(operation)(_ => fail("must not replay")))) shouldBe
      Right(Prepared.Known(StoredOperation(operation, InvocationStatus.InProgress)))
  }

  it should "discard staged registration on rollback and reject reused pending registrations" in {
    val fixture = new Fixture
    val captured = new AtomicReference[Option[PendingPreparation[String]]](None)
    val rolledBack = fixture.protocol.prepare { scope =>
      captured.set(Some(scope.stage(operation)(_ => "context")))
      throw new IllegalStateException("host failed")
    }
    result(rolledBack) shouldBe Left(TransactionFailure.RolledBack)
    fixture.durable.get().row shouldBe None
    result(fixture.protocol.prepare(_ => captured.get().getOrElse(fail("pending missing")))) shouldBe
      Left(TransactionFailure.Rejected(OperationError.InvalidRegistration))
  }

  it should "keep execution results private until commit and recover only status after a lost acknowledgment" in {
    val fixture = new Fixture
    val permit = fixture.permit()
    val commit = fixture.holdCommit()
    val executed = fixture.protocol.execute(permit) { (scope, _) =>
      val tx = scope.transaction
      tx.state.set(tx.state.get().copy(mutations = 1))
      "one-read secret"
    }
    executed.isCompleted shouldBe false
    fixture.durable.get().mutations shouldBe 0
    commit.success(Left(TransactionFailure.CommitUnknown))
    result(executed) shouldBe Left(TransactionFailure.CommitUnknown)
    fixture.durable.get().mutations shouldBe 1
    result(fixture.protocol.reconcile(operation)) shouldBe Right(StoredOperation(operation, InvocationStatus.Committed))
    result(fixture.protocol.execute(permit)((_, _) => fail("must not replay"))) shouldBe
      Left(TransactionFailure.Rejected(OperationError.PermitUsed))
  }

  it should "fence a crashed admitted attempt and a missing preparation without minting another permit" in {
    val fixture = new Fixture
    val permit = fixture.permit()
    result(fixture.protocol.reconcile(operation)) shouldBe Right(StoredOperation(operation, InvocationStatus.NotCommitted))
    result(fixture.protocol.execute(permit)((_, _) => fail("must be fenced"))) shouldBe
      Left(TransactionFailure.Rejected(OperationError.NotPrepared))
    val absent = new Fixture
    result(absent.protocol.reconcile(operation)) shouldBe Right(StoredOperation(operation, InvocationStatus.NotCommitted))
    result(absent.prepare()) shouldBe Right(Prepared.Known(StoredOperation(operation, InvocationStatus.NotCommitted)))
  }

  it should "reject a foreign issuer and changed exact intent without consuming a legitimate permit" in {
    val fixture = new Fixture
    val permit = fixture.permit()
    val foreign = new OperationProtocol(fixture.executor, fixture.store, () => now)
    result(foreign.execute(permit)((_, _) => fail("foreign"))) shouldBe
      Left(TransactionFailure.Rejected(OperationError.ForeignPermit))
    val changed = operation.copy(definition = definition.copy(expiresAt = now.plusSeconds(30)))
    result(fixture.protocol.prepare(scope => scope.stage(changed)(_ => fail("changed metadata")))) shouldBe
      Left(TransactionFailure.Rejected(OperationError.InvocationConflict))
    result(fixture.protocol.execute(permit)((_, _) => "ok")) shouldBe Right("ok")
  }

  it should "roll back host changes when expiry is crossed during execution" in {
    val fixture = new Fixture
    val permit = fixture.permit()
    result(fixture.protocol.execute(permit) { (scope, _) =>
      val tx = scope.transaction
      tx.state.set(tx.state.get().copy(mutations = 1))
      fixture.clock.set(definition.expiresAt)
      "discarded"
    }) shouldBe Left(TransactionFailure.Rejected(OperationError.Expired))
    fixture.durable.get().mutations shouldBe 0
    fixture.durable.get().row.map(_.status) shouldBe Some(InvocationStatus.InProgress)
  }

  it should "preserve direct executor thread confinement while the scope is still active" in {
    val fixture = new Fixture
    val permit = fixture.permit()
    result(fixture.protocol.execute(permit) { (scope, _) =>
      val foreign = Promise[OperationError]()
      val thread = new Thread(() => {
        try {
          scope.transaction
          foreign.failure(new IllegalStateException("foreign thread was accepted"))
        } catch {
          case error: OperationProtocolException => foreign.success(error.error)
        }
        ()
      })
      thread.start()
      result(foreign.future) shouldBe OperationError.ScopeClosed
      // The owner still has a live scope: this was a thread rejection, not close.
      scope.transaction.state.get().mutations shouldBe 0
      "ok"
    }) shouldBe Right("ok")
  }

  it should "keep pending registrations and execution permits unconstructible by callers" in {
    typeChecks("""
      import spoonbill.security.transaction.*
      new ExecutionPermit(new Object(), null)
    """) shouldBe false
    typeChecks("""
      import spoonbill.security.transaction.*
      new PendingPreparation[String](new Object(), Left(null))
    """) shouldBe false
  }

  it should "reject release policies for both exact-attempt preparation kinds" in {
    PreparationKind.values.foreach { kind =>
      val fixture = new Fixture
      val invalid = operation.copy(kind = kind,
        definition = definition.copy(policy = ReservationPolicy.ReleaseAfterDefiniteFailure))
      result(fixture.protocol.prepare(scope => scope.stage(invalid)(_ => fail("invalid policy")))) shouldBe
        Left(TransactionFailure.Rejected(OperationError.InvalidDefinition))
      fixture.lockCalls.get() shouldBe 0
    }
  }

  it should "redact exact metadata and arbitrary prepared host results" in {
    operation.toString shouldBe "PreparedOperation(<redacted>)"
    StoredOperation(operation, InvocationStatus.InProgress).toString shouldBe "StoredOperation(InProgress)"
    val fixture = new Fixture
    result(fixture.protocol.prepare(scope => scope.stage(operation)(_ => "secret-host-result"))) match {
      case Right(prepared) => prepared.toString shouldBe "Prepared.Ready(<redacted>)"
      case other => fail(s"Unexpected result $other")
    }
  }
}
