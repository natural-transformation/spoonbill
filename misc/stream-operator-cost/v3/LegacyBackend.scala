package spoonbill.effect

import scala.concurrent.{ExecutionContext, Future}

/** Compile only this adapter for the original and compact7 revisions. */
object BenchmarkBackend {
  def create(hooks: StreamOperatorBenchmarkV3.Instrumentation): Effect[Future] = {
    class Tracked extends Effect.FutureEffect {
      override def start[A](create: => Future[A])(implicit ec: ExecutionContext): Future[Effect.Fiber[Future, A]] = {
        hooks.beforeStart()
        val result = super.start(create)
        result.foreach(fiber => hooks.afterStart(fiber.join().map(_ => ())(ExecutionContext.parasitic)))(ExecutionContext.parasitic)
        result
      }
    }
    if (!hooks.checked) new Tracked
    else new Tracked {
      override def delay[A](value: => A): Future[A] = super.delay { hooks.check(); value }
      override def map[A, B](value: Future[A])(f: A => B): Future[B] = super.map(value) { a => hooks.check(); f(a) }
      override def flatMap[A, B](value: Future[A])(f: A => Future[B]): Future[B] =
        super.flatMap(value) { a => hooks.check(); f(a) }
    }
  }
}
