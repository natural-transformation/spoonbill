package spoonbill.server.internal.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Scheduler}
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor

class SessionsRequiredSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]               = Effect.futureEffect
  private def await[A](result: Future[A]): A = Await.result(result, 3.seconds)

  private class Timing(inline: Boolean = false) extends SessionsService.RequiredTiming[Future] {
    var cancellations     = 0
    private var scheduled = Option.empty[() => Future[Unit]]
    def fire(): Unit      = await(scheduled.getOrElse(fail("No mandatory deadline was scheduled"))())
    def scheduleOnce(delay: FiniteDuration)(job: => Future[Unit]): Future[Scheduler.JobHandler[Future, Unit]] = {
      delay shouldBe 10.seconds
      scheduled = Some(() => job)
      if (inline) fire()
      Future.successful(new Scheduler.JobHandler[Future, Unit] {
        def unsafeCancel(): Unit   = cancellations += 1
        def cancel(): Future[Unit] = Effect[Future].delay(unsafeCancel())
        def result: Future[Unit]   = Future.unit
      })
    }
  }

  private def service(timing: SessionsService.RequiredTiming[Future]): SessionsService[Future, Int, Unit] = {
    val config = SpoonbillServiceConfig[Future, Int, Unit](stateLoader = StateLoader.default[Future, Int](0))
    new SessionsService(config, null, Some(timing))
  }

  "A mandatory session bound" should "cancel its deadline when success or failure wins and ignore an already dispatched timeout" in {
    for (success <- List(true, false)) {
      val timing      = new Timing
      val failure     = new IllegalStateException("operation failed")
      var expirations = 0
      val operation   = if (success) Future.successful(7) else Future.failed[Int](failure)
      val result      = service(timing).required(operation, expired = () => { expirations += 1; failure })
      if (success) await(result) shouldBe 7 else await(result.failed) should be theSameInstanceAs failure
      timing.cancellations shouldBe 1
      timing.fire()
      expirations shouldBe 0
    }
  }

  it should "settle timeout without the operation and release a later success once" in {
    val timing    = new Timing
    val operation = Promise[Int]()
    val expired   = new IllegalStateException("deadline")
    var released  = Vector.empty[Int]
    val result = service(timing).required(
      operation.future,
      Some(value => Future.successful { released :+= value; () }),
      () => expired
    )
    result.isCompleted shouldBe false
    timing.fire()
    await(result.failed) should be theSameInstanceAs expired
    released shouldBe empty
    operation.success(42)
    released shouldBe Vector(42)
    timing.cancellations shouldBe 0
  }

  it should "ignore a late failed operation without invoking successful-result cleanup" in {
    val timing    = new Timing
    val operation = Promise[Int]()
    var releases  = 0
    val result    = service(timing).required(operation.future, Some(_ => Future.successful { releases += 1; () }))
    timing.fire()
    await(result.failed)
    operation.failure(new IllegalStateException("late operation failure"))
    releases shouldBe 0
  }

  it should "start the operation even when expiry fires before the timer handle is returned" in {
    val timing    = new Timing(inline = true)
    val operation = Promise[Int]()
    var started   = 0
    var released  = Vector.empty[Int]
    val result = service(timing).required(
      { started += 1; operation.future },
      Some(value => Future.successful { released :+= value; () })
    )
    await(result.failed)
    started shouldBe 1
    operation.success(3)
    released shouldBe Vector(3)
    timing.cancellations shouldBe 0
  }

  it should "leave the operation unconstructed when scheduling fails" in {
    val failed = new IllegalStateException("schedule failed")
    val timing = new SessionsService.RequiredTiming[Future] {
      def scheduleOnce(delay: FiniteDuration)(job: => Future[Unit]): Future[Scheduler.JobHandler[Future, Unit]] =
        Future.failed(failed)
    }
    var started = false
    val result  = service(timing).required { started = true; Future.successful(1) }
    await(result.failed) should be theSameInstanceAs failed
    started shouldBe false
  }
}
