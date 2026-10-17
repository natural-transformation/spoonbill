package spoonbill.auth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import spoonbill.Qsid
import spoonbill.action.InvocationBinding
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.security.transaction.*

class BrowserReplacementPreparationSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext            = ExecutionContext.parasitic
  private given Effect[Future]              = new Effect.FutureEffect
  private val now                           = Instant.parse("2026-10-16T12:00:00Z")
  private def id(value: Long): UUID         = new UUID(0L, value)
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected rejection: $error"), identity)

  private type Program[A] = () => A
  private val lazyProgram = new TransactionProgram[Program] {
    def pure[A](value: A): Program[A]                                    = () => value
    def map[A, B](value: Program[A])(f: A => B): Program[B]              = () => f(value())
    def flatMap[A, B](value: Program[A])(f: A => Program[B]): Program[B] = () => f(value())()
    def defer[A](value: => Program[A]): Program[A]                       = () => value()
    def guarantee[A](value: => Program[A])(finalizer: => Unit): Program[A] = () =>
      try value()
      finally finalizer
  }

  private enum Replay {
    case None, Callback, Program
  }

  /**
   * The fixture runner deliberately violates single evaluation in the replay
   * cases. It models settlement acknowledgement, not durable provider storage.
   */
  private class Fixture(replay: Replay = Replay.None) {
    val issuer     = new BrowserCapabilityScope[String]
    val connection = ConnectionId.fromUuid(id(2))
    val identity = issuer.identity(
      "binding",
      Some("predecessor"),
      3L,
      Some(HostPrincipal("name", "subject", 7L, 2L, now.plusSeconds(300))),
      Some(id(1))
    )
    val view             = issuer.view(identity, Qsid("device", "view"), connection, id(3), 4L)
    val binding          = new InvocationBinding(InvocationId.fromUuid(id(4)), connection)
    val invocation       = issuer.replacementInvocation(view, binding)
    val ceremony         = issuer.replacementCeremony(view, CeremonyId(id(5)), "replace-factor", now.plusSeconds(60), None)
    val admissionCalls   = new AtomicInteger()
    val transactionCalls = new AtomicInteger()
    val hostCalls        = new AtomicInteger()
    val acknowledged     = Promise[Either[TransactionFailure, Unit]]()
    val admission        = Promise[Either[TransactionFailure, Unit]]()
    val executor = new TransactionExecutor[Future, Program, Unit] {
      val program     = lazyProgram
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Unit => Program[A]): Future[Either[TransactionFailure, A]] = {
        transactionCalls.incrementAndGet()
        try {
          val work   = body(())
          val staged = work()
          replay match {
            case Replay.None     => ()
            case Replay.Callback => body(())()
            case Replay.Program  => work()
          }
          acknowledged.future.map(_.map(_ => staged))
        } catch {
          case error: OperationProtocolException => Future.successful(Left(TransactionFailure.Rejected(error.error)))
          case NonFatal(_)                       => Future.successful(Left(TransactionFailure.RolledBack))
        }
      }
    }
    val preparation = new BrowserReplacementPreparation(
      issuer,
      executor,
      (_, _) => {
        admissionCalls.incrementAndGet()
        admission.future
      }
    )
    def stage(tx: Unit): Program[UUID] = {
      hostCalls.incrementAndGet()
      () => id(6)
    }
    def run(): Future[Either[TransactionFailure, CommittedReplacement]] =
      preparation.prepare(ceremony, invocation)(stage)
    def allow(): Unit  = { admission.success(Right(())); () }
    def commit(): Unit = { acknowledged.success(Right(())); () }
  }

  "Replacement settlement" should "wait for durable admission and acknowledged outer commit before issuing a handoff" in {
    val fixture = new Fixture
    val result  = fixture.run()
    fixture.admissionCalls.get() shouldBe 1
    fixture.transactionCalls.get() shouldBe 0
    result.isCompleted shouldBe false
    fixture.allow()
    fixture.transactionCalls.get() shouldBe 1
    fixture.hostCalls.get() shouldBe 1
    result.isCompleted shouldBe false
    fixture.commit()
    fixture.issuer.owns(accepted(await(result))) shouldBe true
    await(fixture.run()) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    fixture.hostCalls.get() shouldBe 1
  }

  it should "return no handoff or replay on rejected, rolled-back, unknown or failed settlement" in {
    Vector(
      TransactionFailure.Rejected(OperationError.HostDenied),
      TransactionFailure.RolledBack,
      TransactionFailure.CommitUnknown,
      TransactionFailure.StorageFailure
    ).foreach { failure =>
      val fixture = new Fixture
      fixture.allow()
      val result = fixture.run()
      fixture.acknowledged.success(Left(failure))
      await(result) shouldBe Left(failure)
      await(fixture.run()) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
      fixture.hostCalls.get() shouldBe 1
      fixture.transactionCalls.get() shouldBe 1
    }
  }

  it should "consume local admission even when durable admission refuses the attempt" in {
    val fixture = new Fixture
    fixture.admission.success(Left(TransactionFailure.Rejected(OperationError.Expired)))
    await(fixture.run()) shouldBe Left(TransactionFailure.Rejected(OperationError.Expired))
    await(fixture.run()) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    fixture.admissionCalls.get() shouldBe 1
    fixture.transactionCalls.get() shouldBe 0
    fixture.hostCalls.get() shouldBe 0
  }

  it should "reject a different physical owner before storage or callback work without consuming the valid capability" in {
    val fixture         = new Fixture
    val otherView       = fixture.issuer.view(fixture.identity, fixture.view.qsid, fixture.connection, id(9), 5L)
    val otherInvocation = fixture.issuer.replacementInvocation(otherView, fixture.binding)
    await(fixture.preparation.prepare(fixture.ceremony, otherInvocation)(fixture.stage)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    fixture.admissionCalls.get() shouldBe 0
    fixture.hostCalls.get() shouldBe 0
    fixture.allow()
    fixture.commit()
    fixture.issuer.owns(accepted(await(fixture.run()))) shouldBe true
  }

  it should "reject both callback reconstruction and lazy-program replay before repeating host work" in {
    Vector(Replay.Callback, Replay.Program).foreach { replay =>
      val fixture = new Fixture(replay)
      fixture.allow()
      fixture.commit()
      await(fixture.run()) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
      fixture.hostCalls.get() shouldBe 1
      fixture.transactionCalls.get() shouldBe 1
    }
  }

  it should "propagate construction and program failures through the runner without issuing a handoff" in {
    Vector(false, true).foreach { constructionFailure =>
      val fixture = new Fixture
      fixture.allow()
      val result = fixture.preparation.prepare(fixture.ceremony, fixture.invocation) { _ =>
        fixture.hostCalls.incrementAndGet()
        if (constructionFailure) throw new IllegalStateException("construction failure")
        () => throw new IllegalStateException("program failure")
      }
      await(result) shouldBe Left(TransactionFailure.RolledBack)
      await(fixture.run()) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
      fixture.hostCalls.get() shouldBe 1
    }
  }

  it should "retain ordinary preparation receipt semantics through the shared execution guard" in {
    Vector(Replay.None, Replay.Callback, Replay.Program).foreach { replay =>
      val fixture     = new Fixture(replay)
      val ceremony    = fixture.issuer.ceremony(fixture.view, CeremonyId(id(10)), "login", now.plusSeconds(60), None)
      val preparation = new BrowserPreparation(fixture.issuer, fixture.executor, _ => Future.successful(Right(())))
      val result      = preparation.prepare(ceremony)(fixture.stage)
      if (replay == Replay.None) {
        result.isCompleted shouldBe false
        fixture.commit()
        val receipt = accepted(await(result))
        fixture.issuer.owns(receipt) shouldBe true
        receipt.completionId shouldBe id(6)
      } else await(result) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
      fixture.hostCalls.get() shouldBe 1
    }
  }
}
