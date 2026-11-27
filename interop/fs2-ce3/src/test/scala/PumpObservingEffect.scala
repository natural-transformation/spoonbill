import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.IORuntime
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.ExecutionContext
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect

/**
 * An `IO3Effect` for specs that have to see inside a conversion. It keeps the
 * handle of the pump, as an instance that supervises its fibers would. And it
 * counts the promises that were registered through it.
 */
final class PumpObservingEffect(runtime: IORuntime) extends IO3Effect(runtime) {
  private val pump       = new AtomicReference(IO.unit)
  private val registered = new AtomicInteger

  /** Cancels the pump. It completes when the pump has unwound. */
  def cancelPump: IO[Unit] = IO.defer(pump.get())

  /**
   * Completes when `count` promises are registered. A conversion registers one
   * for the cancel signal of its queue. A pull registers one, and it is in the
   * queue by then if it has to wait.
   */
  def awaitRegistered(count: Int): IO[Unit] =
    IO(registered.get() >= count).flatMap(reached => if (reached) IO.unit else IO.cede *> awaitRegistered(count))

  override def start[A](value: => IO[A])(implicit ec: ExecutionContext): IO[Effect.Fiber[IO, A]] =
    IO.defer(value).startOn(ec).map { fiber =>
      pump.set(fiber.cancel)
      new Effect.Fiber[IO, A] { def join(): IO[A] = fiber.joinWithNever }
    }

  override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
    super.promise { callback =>
      register(callback)
      registered.incrementAndGet()
      ()
    }
}
