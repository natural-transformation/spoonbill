package spoonbill.effect

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

/**
 * A FutureEffect whose callbacks a stream operator may not replace by their
 * plain equivalents: it has to call them.
 */
class OverriddenFutureCallbacks extends Effect.FutureEffect {
  val calls = new AtomicInteger()
  override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Future[A] = {
    calls.incrementAndGet(); super.promise(register)
  }
  override def runAsync[A](value: Future[A])(callback: Either[Throwable, A] => Unit): Unit = {
    calls.incrementAndGet(); super.runAsync(value)(callback)
  }
}
