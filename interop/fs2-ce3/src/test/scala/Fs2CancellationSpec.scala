import _root_.cats.effect.{Deferred, IO}
import _root_.cats.effect.unsafe.IORuntime
import _root_.fs2.{Stream => Fs2Stream}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect
import spoonbill.fs2.*

class Fs2CancellationSpec extends AnyFlatSpec with Matchers {
  private val runtime                 = IORuntime.global
  private def run[A](value: IO[A]): A = Await.result(value.unsafeToFuture()(runtime), 5.seconds)

  "An FS2 producer" should "stop when its returned stream was canceled before the producer started" in {
    given ExecutionContext = ExecutionContext.global
    val startGate          = run(Deferred[IO, Unit])
    val registered         = new CountDownLatch(1)
    val producer           = new AtomicReference(Option.empty[IO[Unit]])
    given Effect[IO] = new IO3Effect(runtime) {
      override def start[A](value: => IO[A])(implicit ec: ExecutionContext): IO[Effect.Fiber[IO, A]] =
        super.start(startGate.get.flatMap(_ => IO.defer(value))).map { fiber =>
          producer.set(Some(fiber.join().map(_ => ())))
          fiber
        }
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
        super.promise { callback =>
          register(callback)
          registered.countDown()
        }
    }
    val stream = run(Fs2Stream.eval(IO.never[Int]).toSpoonbill())
    // The producer is suspended before interruptWhen can register cancelSignal.
    run(stream.cancel())
    run(startGate.complete(()))
    registered.await(5, TimeUnit.SECONDS) shouldBe true
    val joined = producer.get().get.unsafeToFuture()(runtime)
    try Await.result(joined, 3.seconds)
    finally {
      // Also releases the old implementation's late listener after a failing assertion.
      run(stream.cancel())
      Await.result(joined, 3.seconds)
    }
  }
}
