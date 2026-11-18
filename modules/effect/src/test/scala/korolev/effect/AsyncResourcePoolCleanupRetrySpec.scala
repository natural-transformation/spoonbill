package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolCleanupRetrySpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private final class Resource {
    val notification              = Promise[Unit]()
    var closes                    = 0
    var close: () => Future[Unit] = () => Future.unit
  }
  private given Close[Future, Resource] with {
    def onClose(value: Resource): Future[Unit] = value.notification.future
    def close(value: Resource): Future[Unit]   = { value.closes += 1; value.close() }
  }
  private final class Fixture(using Effect[Future]) {
    var now = 0L
    val pool = new AsyncResourcePool[Future, Resource](
      "cleanup-retry",
      Future.successful(new Resource),
      () => Future.successful(now),
      2,
      1.second
    )
    def idle(): Resource = {
      val loan = await(pool.borrow())
      await(loan.give())
      now += 1.second.toNanos
      loan.value
    }
  }

  for (throws <- List(false, true)) {
    "Failed idle retirement" should s"retain a retry handle and release it on success (construction throws: $throws)" in {
      val fixture  = new Fixture
      val resource = fixture.idle()
      val error    = new IllegalStateException("close failed")
      resource.close = () => if (throws) throw error else Future.failed(error)
      await(fixture.pool.cleanup().failed) should be theSameInstanceAs error
      fixture.pool.unsafeGetState.total shouldBe 0
      resource.close = () => Future.unit
      await(fixture.pool.cleanup()) shouldBe 1
      resource.closes shouldBe 2
      fixture.pool.unsafeGetState.closedItems shouldBe empty
      resource.notification.success(())
      fixture.pool.unsafeGetState.total shouldBe 0
      await(fixture.pool.cleanup()) shouldBe 0
    }
  }

  it should "forget a failed retirement when its independent close notification finally arrives" in {
    val fixture  = new Fixture
    val resource = fixture.idle()
    resource.close = () => Future.failed(new IllegalStateException("close failed"))
    await(fixture.pool.cleanup().failed)
    resource.notification.success(())
    fixture.pool.unsafeGetState.total shouldBe 0
    fixture.pool.unsafeGetState.closedItems shouldBe empty
    await(fixture.pool.cleanup()) shouldBe 0
    resource.closes shouldBe 1
  }

  "Concurrent cleanup" should "skip an owned attempt without joining or repeating its close" in {
    val fixture  = new Fixture
    val resource = fixture.idle()
    val entered  = Promise[Unit]()
    val release  = Promise[Unit]()
    resource.close = () => { entered.trySuccess(()); release.future }
    val first = fixture.pool.cleanup()
    await(entered.future)
    await(fixture.pool.cleanup()) shouldBe 0
    resource.closes shouldBe 1
    first.isCompleted shouldBe false
    release.success(())
    await(first) shouldBe 1
    fixture.pool.unsafeGetState.total shouldBe 0
    fixture.pool.unsafeGetState.closedItems shouldBe empty
  }

  "Cleanup startup" should "reject an abandoned late child without taking a retry's ownership" in {
    val rejection                = new IllegalStateException("startup rejected")
    var late: () => Future[Unit] = null
    val effect = new ReaderFiberFutureEffect {
      private var first = true
      override def start[A](task: => Future[A])(implicit ec: ExecutionContext): Future[Effect.Fiber[Future, A]] =
        if (first) {
          first = false
          late = () => task.map(_ => ())(ExecutionContext.parasitic)
          Future.failed(rejection)
        } else super.start(task)
    }
    val fixture  = new Fixture(using effect)
    val resource = fixture.idle()
    val entered  = Promise[Unit]()
    val release  = Promise[Unit]()
    resource.close = () => { entered.trySuccess(()); release.future }
    await(fixture.pool.cleanup().failed) should be theSameInstanceAs rejection
    resource.closes shouldBe 0
    val retry = fixture.pool.cleanup()
    await(entered.future)
    await(late())
    resource.closes shouldBe 1
    await(fixture.pool.cleanup()) shouldBe 0
    release.success(())
    await(retry) shouldBe 1
    fixture.pool.unsafeGetState.closedItems shouldBe empty
  }

  it should "keep an entered child owned when the start observer fails" in {
    val entered             = Promise[Unit]()
    val release             = Promise[Unit]()
    val rejection           = new IllegalStateException("after dispatch")
    var child: Future[Unit] = null
    val effect = Effect.future(Effect.FutureInstrumentation(afterStart = completion => {
      child = completion
      await(entered.future)
      throw rejection
    }))
    val fixture  = new Fixture(using effect)
    val resource = fixture.idle()
    resource.close = () => { entered.trySuccess(()); release.future }
    await(fixture.pool.cleanup().failed) should be theSameInstanceAs rejection
    await(fixture.pool.cleanup()) shouldBe 0
    resource.closes shouldBe 1
    release.success(())
    await(child)
    fixture.pool.unsafeGetState.closedItems shouldBe empty
    fixture.pool.unsafeGetState.total shouldBe 0
  }
}
