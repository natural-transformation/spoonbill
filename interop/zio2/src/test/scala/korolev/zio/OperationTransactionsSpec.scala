package spoonbill.zio

import _root_.zio.{Promise, Task, ZIO}
import _root_.zio.test.{assertTrue, suite, test, ZIOSpecDefault}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.{ExecutionContext, Promise as ScalaPromise}
import spoonbill.effect.Effect
import spoonbill.security.*
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.store.InvocationStatus
import spoonbill.security.transaction.*

/** Real lazy evaluation and native interruption, without a database or blocking
  * bridge. The executor owns resource finalization; the protocol owns scope
  * finalization and rejects replay of an already evaluated transaction program.
  */
object OperationTransactionsSpec extends ZIOSpecDefault {
  private given Effect[Task] = taskEffectInstance(runtime)
  private val now = Instant.parse("2026-10-08T12:00:00Z")
  private def id(n: Long): UUID = new UUID(0L, n)
  private val binding = OperationBinding(SubjectId.fromUuid(id(1)), RealmId.fromUuid(id(2)),
    AuthSessionId.fromUuid(id(3)), SecurityGeneration.initial,
    OperationPurpose.fromUuid(id(4)), ResourceScope.fromUuid(id(5)))
  private val digest = RequestDigest.fromBytes(Array.fill[Byte](32)(1))
    .fold(_ => throw new IllegalStateException("test digest"), identity)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => throw new IllegalStateException(s"Unexpected test rejection: $error"), identity)

  private case class State(record: Option[StoredOperation], mutations: Int)
  private class Tx(val initial: State) {
    val staged = new AtomicReference(initial)
    def mutate(): Unit = staged.set(staged.get().copy(mutations = staged.get().mutations + 1))
  }

  private class Fixture {
    val durable = new AtomicReference(State(None, 0))
    val issuer = new OneUseAuthorityScope(() => now)
    val dispatches = new AtomicInteger()
    val cleanups = new AtomicInteger()
    val outcomes = new AtomicInteger()
    val store = new DurableOperationStore[Task, Tx] {
      def readForDecision(tx: Tx, invocation: OperationInvocation): Task[Option[StoredOperation]] = ZIO.attempt {
        val record = tx.staged.get().record
        record.foreach { row =>
          if (row.operation.invocation != invocation)
            throw new OperationProtocolException(OperationError.InvocationConflict)
        }
        record
      }
      def insertPrepared(tx: Tx, operation: PreparedOperation): Task[Unit] =
        write(tx, operation, InvocationStatus.InProgress)
      def recordOutcome(tx: Tx, operation: PreparedOperation, status: InvocationStatus): Task[Unit] =
        ZIO.succeed(outcomes.incrementAndGet()) *> write(tx, operation, status)
      private def write(tx: Tx, operation: PreparedOperation, status: InvocationStatus): Task[Unit] = ZIO.attempt {
        tx.staged.set(tx.staged.get().copy(record = Some(StoredOperation(operation, status))))
      }
    }
    val executor = new TransactionExecutor[Task, Task, Tx] {
      val program = Zio2TransactionProgram
      val scopePolicy = TransactionScopePolicy.Serialized
      def transact[A](body: Tx => Task[A]): Task[Either[TransactionFailure, A]] =
        ZIO.acquireReleaseWith(ZIO.succeed {
          dispatches.incrementAndGet()
          new Tx(durable.get())
        })(_ => ZIO.succeed { cleanups.incrementAndGet(); () }) { tx =>
          program.defer(body(tx)).map { value =>
            if (durable.compareAndSet(tx.initial, tx.staged.get())) Right(value)
            else Left(TransactionFailure.RolledBack)
          }.catchAll {
            case error: OperationProtocolException => ZIO.succeed(Left(TransactionFailure.Rejected(error.error)))
            case _ => ZIO.succeed(Left(TransactionFailure.RolledBack))
          }
        }
    }
    val protocol = new OneUseOperationProtocol(executor, store, issuer, () => now)
    def authority(): ExecutionAuthority =
      accepted(issuer.issue(accepted(issuer.verified(binding, digest, now.plusSeconds(60)))))
  }

  override def spec = suite("Transaction programs with ZIO 2")(
    test("outer lazy evaluation admits once before dispatch and cannot replay an authority") {
      val fixture = new Fixture
      val calls = new AtomicInteger()
      val attempt = fixture.protocol.executeIssued(fixture.authority()) { (scope, _) =>
        ZIO.attempt {
          calls.incrementAndGet()
          scope.transaction.mutate()
          "private result"
        }
      }
      val deferred = fixture.dispatches.get() == 0 && calls.get() == 0
      for {
        first <- attempt
        second <- attempt
      } yield assertTrue(
        deferred,
        first == Right("private result"),
        second == Left(TransactionFailure.Rejected(OperationError.PermitUsed)),
        fixture.dispatches.get() == 1,
        calls.get() == 1,
        fixture.durable.get().mutations == 1
      )
    },
    test("native interruption closes the scope, releases the transaction, and leaves no invented negative outcome") {
      val fixture = new Fixture
      val authority = fixture.authority()
      val captured = new AtomicReference[Option[TransactionScope[Tx]]](None)
      for {
        entered <- Promise.make[Nothing, Unit]
        running <- fixture.protocol.executeIssued(authority) { (scope, _) =>
          ZIO.attempt {
            captured.set(Some(scope))
            scope.transaction.mutate()
          } *> entered.succeed(()) *> ZIO.never
        }.fork
        _ <- entered.await
        interrupted <- running.interrupt
        scopeResult <- ZIO.attempt(captured.get().getOrElse(throw new IllegalStateException("missing scope")).transaction).either
        beforeReconcile = fixture.durable.get()
        cleanupCount = fixture.cleanups.get()
        reused <- fixture.protocol.executeIssued(authority)((_, _) => ZIO.succeed("must not run"))
        reconciled <- fixture.protocol.reconcile(authority.reference)
      } yield assertTrue(
        interrupted.isInterrupted,
        scopeResult.left.exists {
          case error: OperationProtocolException => error.error == OperationError.ScopeClosed
          case _ => false
        },
        cleanupCount == 1,
        beforeReconcile == State(None, 0),
        reused == Left(TransactionFailure.Rejected(OperationError.PermitUsed)),
        reconciled.map(_.status) == Right(InvocationStatus.NotCommitted)
      )
    },
    test("native interruption finalizes an asynchronous preparation scope") {
      val fixture = new Fixture
      val captured = new AtomicReference[Option[PreparationScope[Task, Tx]]](None)
      val protocol = new OperationProtocol(fixture.executor, fixture.store, () => now)
      val operation = fixture.authority().reference.operation
      for {
        entered <- Promise.make[Nothing, Unit]
        running <- protocol.prepare { scope =>
          captured.set(Some(scope))
          scope.stage(operation)(_ => entered.succeed(()) *> ZIO.never)
        }.fork
        _ <- entered.await
        interrupted <- running.interrupt
        scopeResult <- ZIO.attempt(captured.get().getOrElse(throw new IllegalStateException("missing scope")).transaction).either
      } yield assertTrue(
        interrupted.isInterrupted,
        scopeResult.left.exists {
          case error: OperationProtocolException => error.error == OperationError.ScopeClosed
          case _ => false
        },
        fixture.cleanups.get() == 1,
        fixture.durable.get() == State(None, 0)
      )
    },
    test("interrupting an outer blocking observer leaves its Direct worker owning the eventual transaction outcome") {
      val fixture = new Fixture
      val authority = fixture.authority()
      val entered = ScalaPromise[Unit]()
      val workerFinished = ScalaPromise[Unit]()
      val release = new CountDownLatch(1)
      val calls = new AtomicInteger()
      val closedOnOwner = new AtomicBoolean(false)
      val captured = new AtomicReference[Option[TransactionScope[Tx]]](None)
      val directStore = new DurableOperationStore[Direct, Tx] {
        def readForDecision(tx: Tx, invocation: OperationInvocation): Option[StoredOperation] = {
          val record = tx.staged.get().record
          record.foreach { row =>
            if (row.operation.invocation != invocation)
              throw new OperationProtocolException(OperationError.InvocationConflict)
          }
          record
        }
        def insertPrepared(tx: Tx, operation: PreparedOperation): Unit =
          throw new IllegalStateException("issuer-bound fixture does not prepare")
        def recordOutcome(tx: Tx, operation: PreparedOperation, status: InvocationStatus): Unit =
          tx.staged.set(tx.staged.get().copy(record = Some(StoredOperation(operation, status))))
      }
      val directExecutor = new TransactionExecutor[Task, Direct, Tx] {
        val program = TransactionProgram.direct
        val scopePolicy = TransactionScopePolicy.ThreadConfined
        // The same outer Effect.blocking shape as JdbcTransactionExecutor:
        // interruption stops observing this worker, not its native Direct body.
        def transact[A](body: Tx => A): Task[Either[TransactionFailure, A]] =
          Effect[Task].blocking {
            fixture.dispatches.incrementAndGet()
            val tx = new Tx(fixture.durable.get())
            try {
              val value = body(tx)
              captured.get().foreach { scope =>
                try scope.transaction catch {
                  case error: OperationProtocolException if error.error == OperationError.ScopeClosed =>
                    closedOnOwner.set(true)
                }
              }
              if (fixture.durable.compareAndSet(tx.initial, tx.staged.get())) Right(value)
              else Left(TransactionFailure.RolledBack)
            } catch {
              case error: OperationProtocolException => Left(TransactionFailure.Rejected(error.error))
              case _: IllegalStateException => Left(TransactionFailure.RolledBack)
            } finally {
              fixture.cleanups.incrementAndGet()
              workerFinished.trySuccess(())
            }
          }(ExecutionContext.global)
      }
      val protocol = new OneUseOperationProtocol(directExecutor, directStore, fixture.issuer, () => now)
      val attempt = protocol.executeIssued(authority) { (scope, _) =>
        calls.incrementAndGet()
        captured.set(Some(scope))
        scope.transaction.mutate()
        entered.success(())
        // Bound teardown if the test fails before releasing the worker. Outer
        // interruption need not complete before this native worker terminates.
        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("worker release timed out")
        scope.transaction
        "unobserved private result"
      }
      (for {
        running <- attempt.fork
        _ <- ZIO.fromFuture(_ => entered.future)
        _ <- running.interruptFork
        beforeRelease = fixture.durable.get()
        cleanupBeforeRelease = fixture.cleanups.get()
        reused <- attempt
        _ <- ZIO.succeed(release.countDown())
        _ <- ZIO.fromFuture(_ => workerFinished.future)
        cleanupAfterWorker = fixture.cleanups.get()
        scopeClosedOnOwner = closedOnOwner.get()
        observedExit <- running.await
        reconciled <- protocol.reconcile(authority.reference)
      } yield assertTrue(
        observedExit.isInterrupted,
        beforeRelease == State(None, 0),
        cleanupBeforeRelease == 0,
        cleanupAfterWorker == 1,
        scopeClosedOnOwner,
        calls.get() == 1,
        fixture.durable.get().mutations == 1,
        reused == Left(TransactionFailure.Rejected(OperationError.PermitUsed)),
        reconciled.map(_.status) == Right(InvocationStatus.Committed)
      )).ensuring(ZIO.succeed(release.countDown()))
    },
    test("re-evaluating a lazy transaction program after failure is rejected before host work") {
      val fixture = new Fixture
      val calls = new AtomicInteger()
      val constructions = new AtomicInteger()
      val broken = new TransactionExecutor[Task, Task, Tx] {
        val program = Zio2TransactionProgram
        val scopePolicy = TransactionScopePolicy.Serialized
        def transact[A](body: Tx => Task[A]): Task[Either[TransactionFailure, A]] =
          fixture.executor.transact { tx =>
            constructions.incrementAndGet()
            val original = body(tx)
            original.catchAll(_ => original)
          }
      }
      val protocol = new OneUseOperationProtocol(broken, fixture.store, fixture.issuer, () => now)
      protocol.executeIssued(fixture.authority()) { (scope, _) =>
        ZIO.attempt {
          calls.incrementAndGet()
          scope.transaction.mutate()
          throw new IllegalStateException("synthetic rollback")
        }
      }.map { result =>
        assertTrue(
          result == Left(TransactionFailure.Rejected(OperationError.PermitUsed)),
          constructions.get() == 1,
          calls.get() == 1,
          fixture.durable.get() == State(None, 0),
          fixture.cleanups.get() == 1
        )
      }
    },
    test("domain rejection after a store effect stays a classified failure rather than a defect") {
      val fixture = new Fixture
      val authority = fixture.authority()
      val operation = authority.reference.operation
      val changed = OperationReference(operation.copy(definition = operation.definition.copy(expiresAt = now.plusSeconds(30))))
      for {
        committed <- fixture.protocol.executeIssued(authority)((_, _) => ZIO.succeed("ok"))
        rejected <- fixture.protocol.reconcile(changed)
      } yield assertTrue(
        committed == Right("ok"),
        rejected == Left(TransactionFailure.Rejected(OperationError.InvocationConflict)),
        fixture.cleanups.get() == 2
      )
    },
    test("replaying a successful lazy program cannot commit its first staged mutation") {
      val fixture = new Fixture
      val calls = new AtomicInteger()
      val broken = new TransactionExecutor[Task, Task, Tx] {
        val program = Zio2TransactionProgram
        val scopePolicy = TransactionScopePolicy.Serialized
        def transact[A](body: Tx => Task[A]): Task[Either[TransactionFailure, A]] =
          fixture.executor.transact { tx =>
            val original = body(tx)
            original.flatMap(_ => original)
          }
      }
      val protocol = new OneUseOperationProtocol(broken, fixture.store, fixture.issuer, () => now)
      protocol.executeIssued(fixture.authority()) { (scope, _) =>
        ZIO.attempt {
          calls.incrementAndGet()
          scope.transaction.mutate()
          "staged once"
        }
      }.map { result =>
        assertTrue(
          result == Left(TransactionFailure.Rejected(OperationError.PermitUsed)),
          calls.get() == 1,
          fixture.outcomes.get() == 1,
          fixture.durable.get() == State(None, 0)
        )
      }
    },
    test("guarantee defers construction and finalizes construction failures exactly once") {
      val constructed = new AtomicInteger()
      val finalized = new AtomicInteger()
      val task = Zio2TransactionProgram.guarantee[Unit] {
        constructed.incrementAndGet()
        throw new IllegalStateException("construction failed")
      } { finalized.incrementAndGet(); () }
      val deferred = constructed.get() == 0 && finalized.get() == 0
      task.either.map { result =>
        assertTrue(deferred, result.isLeft, constructed.get() == 1, finalized.get() == 1)
      }
    }
  )
}
