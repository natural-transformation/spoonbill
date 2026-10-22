package spoonbill.effect

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}
import spoonbill.effect.Reporter.PrintReporter.Implicit

final class AsyncResourcePoolFailureSpec extends AnyFlatSpec with Matchers {
  private final class ExpectedFailure(val attempt: Int) extends IllegalStateException(s"Factory attempt $attempt")
  private final class Resource {
    val closed = Promise[Unit]()
  }
  private given Close[Future, Resource] with {
    def onClose(value: Resource): Future[Unit] = value.closed.future
    def close(value: Resource): Future[Unit] = { value.closed.trySuccess(()); Future.unit }
  }
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def failure[A](value: Future[A]): Throwable = Await.ready(value, 5.seconds).value.get match {
    case Failure(error) => error
    case Success(_) => fail("Expected the original factory failure")
  }

  Seq(false, true).foreach { throwsBeforeEffect =>
    "Factory failure handoff" should s"settle a 3000-waiter backlog without recursion when construction throws=$throwsBeforeEffect" in {
      given Effect[Future] = Effect.futureEffect
      val gate = Promise[Resource]()
      val calls = new AtomicInteger()
      val healthy = new AtomicBoolean()
      val errors = Vector.tabulate(3001)(index => new ExpectedFailure(index))
      def factory: Future[Resource] = {
        val index = calls.getAndIncrement()
        if (healthy.get()) Future.successful(new Resource)
        else if (index == 0) gate.future
        else if (throwsBeforeEffect) throw errors(index)
        else Future.failed(errors(index))
      }
      val pool = new AsyncResourcePool[Future, Resource]("failure-backlog", factory,
        () => Future.successful(0L), 1, 1.minute)
      val first = pool.borrow()
      val waiting = Vector.fill(3000)(pool.borrow())
      pool.unsafeGetState.total shouldBe 1
      pool.unsafeGetState.cbs.size shouldBe 3000
      gate.failure(errors.head)
      failure(first) should be theSameInstanceAs errors.head
      waiting.map(failure).toSet shouldBe errors.tail.toSet
      calls.get() shouldBe 3001
      pool.unsafeGetState.total shouldBe 0
      pool.unsafeGetState.cbs shouldBe empty
      pool.unsafeGetState.closedItems shouldBe empty
      healthy.set(true)
      val next = await(pool.borrow())
      pool.unsafeGetState.total shouldBe 1
      await(next.discard())
      pool.unsafeGetState.total shouldBe 0
      pool.unsafeGetState.closedItems shouldBe empty
    }
  }

  it should "settle concurrent asynchronous failures within the reservation bound and recover" in {
    given Effect[Future] = Effect.futureEffect
    val executor = Executors.newFixedThreadPool(4)
    given ExecutionContext = ExecutionContext.fromExecutorService(executor)
    val gates = Vector.fill(4)(Promise[Resource]())
    val calls = new AtomicInteger()
    val active = new AtomicInteger()
    val peak = new AtomicInteger()
    val healthy = new AtomicBoolean()
    val errors = Vector.tabulate(3004)(index => new ExpectedFailure(index))
    def factory: Future[Resource] = {
      val index = calls.getAndIncrement()
      val count = active.incrementAndGet()
      peak.updateAndGet(previous => math.max(previous, count))
      val result =
        if (healthy.get()) Future.successful(new Resource)
        else if (index < gates.size) gates(index).future
        else Future[Resource](throw errors(index))
      result.andThen { case _ => active.decrementAndGet() }(ExecutionContext.parasitic)
    }
    val pool = new AsyncResourcePool[Future, Resource]("async-failure-backlog", factory,
      () => Future.successful(0L), 4, 1.minute)
    try {
      val first = Vector.fill(4)(pool.borrow())
      // Queue borrowers from several threads while all initial creations wait.
      val waiting = await(Future.sequence(Vector.fill(3000)(Future(pool.borrow()))))
      pool.unsafeGetState.total shouldBe 4
      pool.unsafeGetState.cbs.size shouldBe 3000
      await(Future.sequence(gates.zipWithIndex.map { case (gate, index) => Future(gate.failure(errors(index))) }))
      (first ++ waiting).map(failure).toSet shouldBe errors.toSet
      calls.get() shouldBe 3004
      active.get() shouldBe 0
      peak.get() should be <= 4
      pool.unsafeGetState.total shouldBe 0
      pool.unsafeGetState.cbs shouldBe empty
      pool.unsafeGetState.closedItems shouldBe empty
      healthy.set(true)
      val next = await(pool.borrow())
      await(next.discard())
      pool.unsafeGetState.total shouldBe 0
      pool.unsafeGetState.closedItems shouldBe empty
    } finally executor.shutdownNow()
  }

  it should "keep failed reservations released when an effect callback reports a defect" in {
    val reported = new AtomicInteger()
    val inject = new AtomicBoolean(true)
    given Effect[Future] = new Effect.FutureEffect {
      override def runAsync[A](value: Future[A])(callback: Either[Throwable, A] => Unit): Unit = {
        val callbackContext = new ExecutionContext {
          def execute(task: Runnable): Unit = task.run()
          def reportFailure(error: Throwable): Unit = { reported.incrementAndGet(); () }
        }
        value.onComplete { result =>
          callback(result.toEither)
          if (result.isFailure && inject.compareAndSet(true, false))
            throw new IllegalStateException("Synthetic callback defect after delivering failure")
        }(callbackContext)
      }
    }
    val gate = Promise[Resource]()
    val calls = new AtomicInteger()
    val expected = new ExpectedFailure(0)
    def factory: Future[Resource] = if (calls.getAndIncrement() == 0) gate.future else Future.failed(expected)
    val pool = new AsyncResourcePool[Future, Resource]("callback-failure-backlog", factory,
      () => Future.successful(0L), 1, 1.minute)
    val requests = pool.borrow() +: Vector.fill(1000)(pool.borrow())
    gate.failure(expected)
    requests.foreach(value => failure(value) should be theSameInstanceAs expected)
    reported.get() shouldBe 1
    pool.unsafeGetState.total shouldBe 0
    pool.unsafeGetState.cbs shouldBe empty
  }
}
