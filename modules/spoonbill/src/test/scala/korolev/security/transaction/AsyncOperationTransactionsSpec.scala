package spoonbill.security

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.store.InvocationStatus
import spoonbill.security.transaction.*

/** No JDBC, blocking transaction callbacks, or pessimistic locks: the entire
  * fixture document (business state, aliases, capacity and outcomes) commits by
  * one compare-and-set. A conflict rolls back and NEVER replays its program.
  */
class AsyncOperationTransactionsSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private given ExecutionContext = ExecutionContext.parasitic
  private val now = Instant.parse("2026-10-08T12:00:00Z")
  private def id(n: Long): UUID = new UUID(0L, n)
  private val binding = OperationBinding(SubjectId.fromUuid(id(1)), RealmId.fromUuid(id(2)),
    AuthSessionId.fromUuid(id(3)), SecurityGeneration.initial,
    OperationPurpose.fromUuid(id(4)), ResourceScope.fromUuid(id(5)))
  private val digest = RequestDigest.fromBytes(Array.fill[Byte](32)(1)).toOption.getOrElse(fail("digest"))
  private def accepted[E, A](value: Either[E, A]): A = value.fold(error => fail(s"Unexpected rejection: $error"), identity)
  private def result[A](value: Future[A]): A = Await.result(value, 5.seconds)

  private val asyncProgram: TransactionProgram[Future] = new TransactionProgram[Future] {
    def pure[A](value: A): Future[A] = Future.successful(value)
    def map[A, B](value: Future[A])(f: A => B): Future[B] = value.map(f)
    def flatMap[A, B](value: Future[A])(f: A => Future[B]): Future[B] = value.flatMap(f)
    def defer[A](value: => Future[A]): Future[A] =
      try value catch { case NonFatal(error) => Future.failed(error) }
    def guarantee[A](value: => Future[A])(finalizer: => Unit): Future[A] =
      defer(value).transform { completed => finalizer; completed }
  }

  private case class State(records: Map[InvocationId, StoredOperation], mutations: Int)
  private class Tx(val initial: State) {
    val staged = new AtomicReference(initial)
    def mutate(): Unit = staged.set(staged.get().copy(mutations = staged.get().mutations + 1))
  }

  private class Fixture(maxRecords: Int = 8) {
    val durable = new AtomicReference(State(Map.empty, 0))
    val clock = new AtomicReference(now)
    val issuer = new OneUseAuthorityScope(() => clock.get())
    val outcomes = new AtomicInteger()
    val reads = new AtomicInteger()
    val dispatches = new AtomicInteger()
    val readGate = new AtomicReference(Future.successful(()))
    val writeGate = new AtomicReference(Future.successful(()))
    val nextCommit = new AtomicReference[Option[Promise[Either[TransactionFailure, Unit]]]](None)
    val store = new DurableOperationStore[Future, Tx] {
      def readForDecision(tx: Tx, invocation: OperationInvocation): Future[Option[StoredOperation]] = {
        reads.incrementAndGet()
        readGate.get().map { _ =>
          val aliases = tx.staged.get().records.valuesIterator.filter { stored =>
            stored.operation.invocation.invocationId == invocation.invocationId ||
              stored.operation.invocation.grantId == invocation.grantId
          }.toVector
          if (aliases.size > 1 || aliases.exists(_.operation.invocation != invocation))
            throw new OperationProtocolException(OperationError.InvocationConflict)
          aliases.headOption
        }
      }
      def insertPrepared(tx: Tx, operation: PreparedOperation): Future[Unit] =
        write(tx, operation, InvocationStatus.InProgress)
      def recordOutcome(tx: Tx, operation: PreparedOperation, status: InvocationStatus): Future[Unit] = {
        outcomes.incrementAndGet()
        write(tx, operation, status)
      }
      private def write(tx: Tx, operation: PreparedOperation, status: InvocationStatus): Future[Unit] = writeGate.get().map { _ =>
        val current = tx.staged.get()
        val key = operation.invocation.invocationId
        if (!current.records.contains(key) && current.records.size >= maxRecords)
          throw new OperationProtocolException(OperationError.CapacityExceeded)
        current.records.get(key).foreach { existing =>
          if (existing.operation != operation)
            throw new OperationProtocolException(OperationError.InvocationConflict)
        }
        tx.staged.set(current.copy(records = current.records.updated(key, StoredOperation(operation, status))))
      }
    }
    val executor = new TransactionExecutor[Future, Future, Tx] {
      val program = asyncProgram
      val scopePolicy = TransactionScopePolicy.Serialized
      def transact[A](body: Tx => Future[A]): Future[Either[TransactionFailure, A]] = {
        dispatches.incrementAndGet()
        val tx = new Tx(durable.get())
        val acknowledge = nextCommit.getAndSet(None)
          .fold(Future.successful[Either[TransactionFailure, Unit]](Right(())))(_.future)
        program.defer(body(tx)).flatMap { value =>
          acknowledge.map {
            case Left(failure) => Left(failure)
            case Right(_) =>
              if (durable.compareAndSet(tx.initial, tx.staged.get())) Right(value)
              else Left(TransactionFailure.RolledBack)
          }
        }.recover {
          case error: OperationProtocolException => Left(TransactionFailure.Rejected(error.error))
          case NonFatal(_) => Left(TransactionFailure.RolledBack)
        }
      }
    }
    val protocol = new OneUseOperationProtocol(executor, store, issuer, () => clock.get())
    val preparedProtocol = new OperationProtocol(executor, store, () => clock.get())
    def authority(): ExecutionAuthority =
      accepted(issuer.issue(accepted(issuer.verified(binding, digest, now.plusSeconds(60)))))
    def holdCommit(): Promise[Either[TransactionFailure, Unit]] = {
      val gate = Promise[Either[TransactionFailure, Unit]]()
      nextCommit.set(Some(gate))
      gate
    }
  }

  "Asynchronous transactions" should "join reads and mutation across threads, then withhold results until commit acknowledgement" in {
    val fixture = new Fixture
    val read = Promise[Unit]()
    fixture.readGate.set(read.future)
    val commit = fixture.holdCommit()
    val mutation = Promise[String]()
    val captured = new AtomicReference[Option[TransactionScope[Tx]]](None)
    val executed = fixture.protocol.executeIssued(fixture.authority()) { (scope, _) =>
      captured.set(Some(scope))
      mutation.future
    }
    captured.get() shouldBe None
    executed.isCompleted shouldBe false
    read.success(())
    val scope = captured.get().getOrElse(fail("host program was not entered"))
    fixture.outcomes.get() shouldBe 0
    fixture.durable.get().mutations shouldBe 0
    val enteredOn = Thread.currentThread()
    val hopped = Promise[Thread]()
    val worker = new Thread(() => {
      try {
        scope.transaction.mutate()
        mutation.success("private result")
        hopped.success(Thread.currentThread())
      } catch { case NonFatal(error) => hopped.failure(error) }
      ()
    })
    worker.start()
    result(hopped.future) should not be enteredOn
    fixture.outcomes.get() shouldBe 1
    executed.isCompleted shouldBe false
    fixture.durable.get().mutations shouldBe 0
    intercept[OperationProtocolException](scope.transaction).error shouldBe OperationError.ScopeClosed
    // Commit acknowledgement may arrive after expiry: joined work was fresh.
    fixture.clock.set(now.plusSeconds(60))
    commit.success(Right(()))
    result(executed) shouldBe Right("private result")
    fixture.durable.get().mutations shouldBe 1
  }

  it should "close suspended scopes on failed construction and failed programs without recording success" in {
    for (constructionFailure <- Vector(false, true)) {
      val fixture = new Fixture
      val authority = fixture.authority()
      val captured = new AtomicReference[Option[TransactionScope[Tx]]](None)
      val failure = Promise[String]()
      val executed = fixture.protocol.executeIssued(authority) { (scope, _) =>
        captured.set(Some(scope))
        scope.transaction.mutate()
        if (constructionFailure) throw new IllegalStateException("construction failed")
        failure.future
      }
      if (!constructionFailure) failure.failure(new IllegalStateException("program failed"))
      result(executed) shouldBe Left(TransactionFailure.RolledBack)
      fixture.durable.get().mutations shouldBe 0
      fixture.outcomes.get() shouldBe 0
      val scope = captured.get().getOrElse(fail("missing scope"))
      intercept[OperationProtocolException](scope.transaction).error shouldBe OperationError.ScopeClosed
      result(fixture.protocol.executeIssued(authority)((_, _) => fail("must not retry"))) shouldBe
        Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    }
  }

  it should "recheck expiry after an awaited host mutation" in {
    val fixture = new Fixture
    val mutation = Promise[Unit]()
    val executed = fixture.protocol.executeIssued(fixture.authority()) { (scope, _) =>
      scope.transaction.mutate()
      mutation.future
    }
    fixture.clock.set(now.plusSeconds(60))
    mutation.success(())
    result(executed) shouldBe Left(TransactionFailure.Rejected(OperationError.Expired))
    fixture.durable.get().mutations shouldBe 0
    fixture.outcomes.get() shouldBe 0
  }

  it should "publish preparation only after its joined program and acknowledged outer commit" in {
    val fixture = new Fixture
    val operation = fixture.authority().reference.operation
    val commit = fixture.holdCommit()
    val host = Promise[String]()
    val captured = new AtomicReference[Option[PreparationScope[Future, Tx]]](None)
    val prepared = fixture.preparedProtocol.prepare { scope =>
      captured.set(Some(scope))
      scope.stage(operation)(_ => host.future)
    }
    prepared.isCompleted shouldBe false
    fixture.durable.get().records shouldBe empty
    host.success("prepared context")
    prepared.isCompleted shouldBe false
    val scope = captured.get().getOrElse(fail("missing preparation scope"))
    intercept[OperationProtocolException](scope.transaction).error shouldBe OperationError.ScopeClosed
    result(scope.stage(operation)(_ => Future.successful(())).failed) match {
      case error: OperationProtocolException => error.error shouldBe OperationError.ScopeClosed
      case other => fail(s"Unexpected scope failure: $other")
    }
    commit.success(Right(()))
    result(prepared) match {
      case Right(Prepared.Ready("prepared context", _)) => succeed
      case other => fail(s"Expected ready preparation, got $other")
    }
  }

  it should "exclude a suspended writer after a missing-record negative fence wins conditional commit" in {
    val fixture = new Fixture
    val authority = fixture.authority()
    val finish = Promise[String]()
    val calls = new AtomicInteger()
    val executed = fixture.protocol.executeIssued(authority) { (scope, _) =>
      calls.incrementAndGet()
      scope.transaction.mutate()
      finish.future
    }
    result(fixture.protocol.reconcile(authority.reference)).map(_.status) shouldBe Right(InvocationStatus.NotCommitted)
    finish.success("must not escape")
    result(executed) shouldBe Left(TransactionFailure.RolledBack)
    fixture.durable.get().mutations shouldBe 0
    fixture.durable.get().records.values.map(_.status).toSet shouldBe Set(InvocationStatus.NotCommitted)
    result(fixture.protocol.executeIssued(authority)((_, _) => fail("must not replay"))) shouldBe
      Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    calls.get() shouldBe 1
  }

  it should "recheck preparation expiry after outer work following stage and still allow expired status reads" in {
    val fixture = new Fixture
    val operation = fixture.authority().reference.operation
    val afterStage = Promise[Unit]()
    val prepared = fixture.preparedProtocol.prepare { scope =>
      scope.stage(operation) { tx =>
        tx.mutate()
        Future.successful("staged context")
      }.flatMap(pending => afterStage.future.map(_ => pending))
    }
    prepared.isCompleted shouldBe false
    fixture.clock.set(now.plusSeconds(60))
    afterStage.success(())
    result(prepared) shouldBe Left(TransactionFailure.Rejected(OperationError.Expired))
    fixture.durable.get().mutations shouldBe 0
    fixture.durable.get().records shouldBe empty

    result(fixture.preparedProtocol.reconcile(operation)).map(_.status) shouldBe Right(InvocationStatus.NotCommitted)
    result(fixture.preparedProtocol.prepare { scope =>
      scope.stage(operation)(_ => fail("known identity must not prepare again"))
    }) shouldBe Right(Prepared.Known(StoredOperation(operation, InvocationStatus.NotCommitted)))
  }

  for (preparation <- Vector(false, true)) {
    it should s"recheck expiry after an awaited ${if (preparation) "preparation" else "outcome"} write" in {
      val fixture = new Fixture
      val authority = fixture.authority()
      val write = Promise[Unit]()
      fixture.writeGate.set(write.future)
      val attempt = if (preparation) {
        fixture.preparedProtocol.prepare { scope =>
          scope.stage(authority.reference.operation) { tx =>
            tx.mutate()
            Future.successful(())
          }
        }
      } else {
        fixture.protocol.executeIssued(authority) { (scope, _) =>
          scope.transaction.mutate()
          Future.successful(())
        }
      }
      attempt.isCompleted shouldBe false
      fixture.clock.set(now.plusSeconds(60))
      write.success(())
      result(attempt) shouldBe Left(TransactionFailure.Rejected(OperationError.Expired))
      fixture.durable.get().mutations shouldBe 0
      fixture.durable.get().records shouldBe empty
    }
  }

  it should "reject a stale negative fence when the original writer wins conditional commit" in {
    val fixture = new Fixture
    val authority = fixture.authority()
    val finish = Promise[String]()
    val executed = fixture.protocol.executeIssued(authority) { (scope, _) =>
      scope.transaction.mutate()
      finish.future
    }
    val commitNegative = fixture.holdCommit()
    val reconciled = fixture.protocol.reconcile(authority.reference)
    reconciled.isCompleted shouldBe false
    finish.success("acknowledged")
    result(executed) shouldBe Right("acknowledged")
    commitNegative.success(Right(()))
    result(reconciled) shouldBe Left(TransactionFailure.RolledBack)
    result(fixture.protocol.reconcile(authority.reference)).map(_.status) shouldBe Right(InvocationStatus.Committed)
    fixture.durable.get().mutations shouldBe 1
  }

  it should "protect aliases, exact intent and retained capacity in the same atomic document" in {
    val fixture = new Fixture(maxRecords = 1)
    val authority = fixture.authority()
    result(fixture.protocol.executeIssued(authority) { (scope, _) =>
      scope.transaction.mutate()
      Future.successful("ok")
    }) shouldBe Right("ok")
    val operation = authority.reference.operation
    val aliased = OperationReference(operation.copy(invocation = operation.invocation.copy(
      invocationId = InvocationId.fromUuid(id(999)))))
    result(fixture.protocol.reconcile(aliased)) shouldBe Left(TransactionFailure.Rejected(OperationError.InvocationConflict))
    val changed = OperationReference(operation.copy(definition = operation.definition.copy(expiresAt = now.plusSeconds(30))))
    result(fixture.protocol.reconcile(changed)) shouldBe Left(TransactionFailure.Rejected(OperationError.InvocationConflict))
    val another = fixture.authority()
    result(fixture.protocol.executeIssued(another) { (scope, _) =>
      scope.transaction.mutate()
      Future.successful("must roll back")
    }) shouldBe Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
    fixture.durable.get().mutations shouldBe 1
    fixture.durable.get().records.size shouldBe 1
    result(fixture.protocol.executeIssued(another)((_, _) => fail("must not replay"))) shouldBe
      Left(TransactionFailure.Rejected(OperationError.PermitUsed))
  }
}
