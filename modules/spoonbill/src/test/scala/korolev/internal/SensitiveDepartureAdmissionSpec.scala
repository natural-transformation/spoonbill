package spoonbill.internal

import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import spoonbill.action.*
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.security.Identifiers.ConnectionId

class SensitiveDepartureAdmissionSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val reporter: Reporter = Reporter.PrintReporter
  private val deadlines = new Frontend.RpcDeadlineScheduler {
    def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = () => ()
  }

  private def fixture(authorize: () => Future[Unit] = () => Future.unit,
    ready: () => Future[Unit] = () => Future.unit) = {
    val incoming = Queue[Future, String]()
    val frontend = new Frontend[Future](incoming.stream, Some(2),
      connectionId = Some(ConnectionId.fromUuid(UUID.randomUUID())),
      authorize = Some(authorize), beforeUserCallback = Some(ready),
      rpcDeadlineScheduler = Some(deadlines))
    (incoming, frontend)
  }

  private def bounded[A](frontend: Frontend[Future], release: () => Unit)(operation: => Future[A]): Future[A] = {
    val executor = Executors.newSingleThreadScheduledExecutor((run: Runnable) => {
      val thread = new Thread(run, "sensitive-admission-test-timeout")
      thread.setDaemon(true)
      thread
    })
    def timeout[B](future: Future[B], seconds: Long): Future[B] = {
      val result = Promise[B]()
      val timer = executor.schedule(new Runnable {
        def run(): Unit = { result.tryFailure(new TimeoutException("Sensitive admission regression stalled")); () }
      }, seconds, TimeUnit.SECONDS)
      future.onComplete { outcome => timer.cancel(false); result.tryComplete(outcome); () }
      result.future
    }
    timeout(Future.unit.flatMap(_ => operation), 5L).transformWith { outcome =>
      release()
      timeout(frontend.close(), 3L).transformWith { cleanup =>
        executor.shutdownNow()
        Future.fromTry(outcome.flatMap(value => cleanup.map(_ => value)))
      }
    }
  }

  private def next(frontend: Frontend[Future]): Future[String] =
    frontend.outgoingMessages.pull().map(_.getOrElse(fail("Closed before expected control frame")))

  private def depart(incoming: Queue[Future, String], frontend: Frontend[Future]): Future[Unit] =
    for {
      _ <- incoming.enqueue("[10,\"1\"]")
      _ <- incoming.enqueue("[6]")
      heartbeat <- next(frontend)
    } yield {
      heartbeat shouldBe "[16]"
      ()
    }

  private def callbackWaitingAt(readiness: Boolean): Future[org.scalatest.Assertion] = {
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val checks = new AtomicInteger(0)
    val consumed = new AtomicInteger(0)
    val fresh = Promise[Unit]()
    def gate(): Future[Unit] =
      if (checks.incrementAndGet() == 1) {
        entered.trySuccess(())
        release.future
      } else Future.unit
    val (incoming, frontend) =
      if (readiness) fixture(ready = () => gate()) else fixture(authorize = () => gate())
    bounded(frontend, () => { release.trySuccess(()); () }) {
      for {
        _ <- frontend.registerCustomCallback("consume") { value =>
          consumed.incrementAndGet()
          if (value == "fresh") fresh.trySuccess(())
          Future.unit
        }
        _ <- incoming.enqueue("[1,\"consume:old\"]")
        _ <- entered.future
        _ = consumed.get() shouldBe 0
        // A heartbeat behind departure proves transport processed the fence
        // while the application admission gate is still waiting.
        _ <- depart(incoming, frontend)
        _ = consumed.get() shouldBe 0
        _ = release.success(())
        barrier <- next(frontend)
        _ = barrier shouldBe "[25,1]"
        _ = consumed.get() shouldBe 0
        _ <- incoming.enqueue("[1,\"consume:fresh\"]")
        _ <- fresh.future
      } yield consumed.get() shouldBe 1
    }
  }

  "Sensitive departure admission" should "discard a custom callback waiting for authorization and accept a fresh post-barrier callback" in {
    callbackWaitingAt(readiness = false)
  }

  it should "discard a custom callback waiting for readiness and accept a fresh post-barrier callback" in {
    callbackWaitingAt(readiness = true)
  }

  private enum TypedGate {
    case Policy, Resolve, Revalidate
  }

  private def typedGateWaiting(authenticated: Boolean, waitAt: TypedGate = TypedGate.Policy,
    releaseDecision: AccessDecision = AccessDecision.Allowed): Future[org.scalatest.Assertion] = {
    val entered = Promise[Unit]()
    val release = Promise[AccessDecision]()
    val checks = new AtomicInteger(0)
    val consumed = new AtomicInteger(0)
    val (incoming, frontend) = fixture()
    def gate(at: TypedGate): Future[AccessDecision] =
      if (at == waitAt && checks.incrementAndGet() == 1) {
        entered.trySuccess(())
        release.future
      } else Future.successful(AccessDecision.Allowed)
    val actions = new Actions[Future, Int, Unit]
    val name = ActionName.parse("admission.consume").fold(error => fail(error.toString), identity)
    def consume(): Future[UiOutcome[Int]] = {
      // A synthetic one-read grant is spent only by the domain handler.
      consumed.incrementAndGet()
      Future.successful(UiOutcome.unchanged[Int])
    }
    val publicAction = actions.public(name, InputSchema.empty, PublicPolicy[Future, Unit](_ => gate(TypedGate.Policy))) {
      (_, _) => consume()
    }
    val protectedAction = actions.authenticated(name, InputSchema.empty,
      ActionPolicy[Future, Unit, Unit]((_, _) => gate(TypedGate.Policy))) {
      (_, _) => consume()
    }
    val authority = new SessionAuthority[Future, Unit] {
      def resolve(binding: InvocationBinding): Future[Either[AccessDenied, Unit]] = gate(TypedGate.Resolve).map {
        case AccessDecision.Allowed => Right(())
        case AccessDecision.Denied(reason) => Left(reason)
      }
      def revalidate(binding: InvocationBinding, principal: Unit) = gate(TypedGate.Revalidate)
    }
    def dispatch(binding: InvocationBinding): Future[InvocationResult[Int]] =
      if (authenticated) ActionDispatcher.authenticated(protectedAction, Vector.empty, binding, authority)
      else ActionDispatcher.public(publicAction, Vector.empty, binding)
    bounded(frontend, () => { release.trySuccess(AccessDecision.Allowed); () }) {
      val pending = frontend.runUserAction(frontend.newActionBinding(Some(0L)).flatMap(dispatch))
      for {
        _ <- entered.future
        _ = consumed.get() shouldBe 0
        _ <- depart(incoming, frontend)
        _ = consumed.get() shouldBe 0
        _ = release.success(releaseDecision)
        old <- pending
        _ = old shouldBe InvocationResult.Superseded()
        barrier <- next(frontend)
        _ = barrier shouldBe "[25,1]"
        _ = consumed.get() shouldBe 0
        fresh <- frontend.runUserAction(frontend.newActionBinding(Some(1L)).flatMap(dispatch))
      } yield {
        fresh match {
          case InvocationResult.Completed(_) => succeed
          case other => fail(s"Fresh action was not admitted: $other")
        }
        consumed.get() shouldBe 1
      }
    }
  }

  it should "preserve a grant when a public action policy is waiting at departure" in {
    typedGateWaiting(authenticated = false)
  }

  it should "preserve a grant when an authenticated action policy is waiting at departure" in {
    typedGateWaiting(authenticated = true)
  }

  it should "supersede a late public policy denial without producing a rejection result" in {
    typedGateWaiting(authenticated = false, releaseDecision = AccessDecision.Denied(AccessDenied.Forbidden))
  }

  it should "preserve a grant when authenticated authority resolution is waiting at departure" in {
    typedGateWaiting(authenticated = true, waitAt = TypedGate.Resolve)
  }

  it should "preserve a grant when authenticated authority revalidation is waiting at departure" in {
    typedGateWaiting(authenticated = true, waitAt = TypedGate.Revalidate)
  }

  it should "retain the original generation when authenticated binding creation awaits its guard" in {
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val checks = new AtomicInteger(0)
    val consumed = new AtomicInteger(0)
    val (incoming, frontend) = fixture(authorize = () =>
      if (checks.incrementAndGet() == 1) {
        entered.trySuccess(())
        release.future
      } else Future.unit)
    val action = new Actions[Future, Int, Unit].public(
      ActionName.parse("admission.consume").fold(error => fail(error.toString), identity),
      InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, _) =>
      consumed.incrementAndGet()
      Future.successful(UiOutcome.unchanged[Int])
    }
    bounded(frontend, () => { release.trySuccess(()); () }) {
      // Omit an explicit generation: the runtime must capture it before the
      // asynchronous guard, rather than adopting the generation after recovery.
      val pending = frontend.newAuthenticatedActionBinding()
      for {
        _ <- entered.future
        _ <- incoming.enqueue("[10,\"1\"]")
        barrier <- next(frontend)
        _ = barrier shouldBe "[25,1]"
        _ = release.success(())
        binding <- pending
        old <- ActionDispatcher.public(action, Vector.empty, binding)
        _ = old shouldBe InvocationResult.Superseded()
        _ = consumed.get() shouldBe 0
        freshBinding <- frontend.newAuthenticatedActionBinding()
        fresh <- ActionDispatcher.public(action, Vector.empty, freshBinding)
      } yield {
        fresh match {
          case InvocationResult.Completed(_) => succeed
          case other => fail(s"Fresh action was not admitted: $other")
        }
        consumed.get() shouldBe 1
      }
    }
  }
}
