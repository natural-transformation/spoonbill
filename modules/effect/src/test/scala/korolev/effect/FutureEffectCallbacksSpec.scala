package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future}

class FutureEffectCallbacksSpec extends AnyFlatSpec with Matchers {
  private trait Observed extends Effect.FutureEffect {
    override def runAsync[A](value: Future[A])(callback: Either[Throwable, A] => Unit): Unit =
      super.runAsync(value)(callback)
  }

  "FutureEffect's own callbacks" should "be recognized in it and in subclasses that leave them alone" in {
    Effect.FutureEffect.keepsCallbacks(Effect.futureEffect) shouldBe true
    Effect.FutureEffect.keepsCallbacks(new Effect.FutureEffect) shouldBe true
    Effect.FutureEffect.keepsCallbacks(new Effect.FutureEffect {
      override def start[A](task: => Future[A])(implicit ec: ExecutionContext): Future[Effect.Fiber[Future, A]] =
        super.start(task)
      override def map[A, B](value: Future[A])(f: A => B): Future[B] = super.map(value)(f)
    }) shouldBe true
  }

  it should "not be assumed once a subclass replaces promise, runAsync or fail" in {
    Effect.FutureEffect.keepsCallbacks(new Effect.FutureEffect {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Future[A] = super.promise(register)
    }) shouldBe false
    Effect.FutureEffect.keepsCallbacks(new Effect.FutureEffect {
      override def runAsync[A](value: Future[A])(callback: Either[Throwable, A] => Unit): Unit =
        super.runAsync(value)(callback)
    }) shouldBe false
    Effect.FutureEffect.keepsCallbacks(new Effect.FutureEffect {
      override def fail[A](error: Throwable): Future[A] = super.fail(error)
    }) shouldBe false
    Effect.FutureEffect.keepsCallbacks(new Effect.FutureEffect with Observed) shouldBe false
    Effect.FutureEffect.keepsCallbacks(new OverriddenFutureCallbacks) shouldBe false
    Effect.FutureEffect.keepsCallbacks(new OverriddenFutureCallbacks {}) shouldBe false
  }
}
