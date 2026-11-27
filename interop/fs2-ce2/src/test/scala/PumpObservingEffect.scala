import _root_.cats.effect.{ContextShift, IO}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.ExecutionContext
import spoonbill.cats.IOEffect
import spoonbill.effect.Effect

/**
 * `IOEffect` for specs that have to see inside a conversion. It keeps the
 * handle of the pump, as an instance that supervises its fibers would. And it
 * counts the promises that were registered through it.
 */
final class PumpObservingEffect(using ContextShift[IO]) extends Effect[IO] {
  export IOEffect.{promise as _, start as _, *}
  private val pump       = new AtomicReference(IO.unit)
  private val registered = new AtomicInteger

  /** Cancels the pump. */
  def cancelPump: IO[Unit] = IO.defer(pump.get())

  /**
   * Completes when `count` promises are registered. A conversion registers one
   * for the cancel signal of its queue. A pull registers one, and it is in the
   * queue by then if it has to wait.
   */
  def awaitRegistered(count: Int): IO[Unit] =
    IO(registered.get() >= count).flatMap(reached => if (reached) IO.unit else IO.shift *> awaitRegistered(count))

  // The export leaves out what `Effect` defines itself.
  override def guarantee[A](task: IO[A])(finalizer: => IO[Unit]): IO[A] = IOEffect.guarantee(task)(finalizer)

  def start[A](create: => IO[A])(implicit ec: ExecutionContext): IO[Effect.Fiber[IO, A]] =
    IO.defer(create).start.map { fiber =>
      pump.set(fiber.cancel)
      new Effect.Fiber[IO, A] { def join(): IO[A] = fiber.join }
    }

  def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
    IOEffect.promise { callback =>
      register(callback)
      registered.incrementAndGet()
      ()
    }
}
