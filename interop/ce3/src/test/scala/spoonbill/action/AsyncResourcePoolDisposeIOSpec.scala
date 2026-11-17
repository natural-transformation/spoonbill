package spoonbill.action

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.IORuntime
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.{AsyncResourcePool, Close, Effect}
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolDisposeIOSpec extends AnyFlatSpec with Matchers {
  private val runtime                 = IORuntime.global
  private def run[A](value: IO[A]): A = Await.result(value.unsafeToFuture()(runtime), 5.seconds)

  "Pool disposal observation" should "register and settle with a lazy effect" in {
    val registered = new CountDownLatch(1)
    given Effect[IO] = new IO3Effect(runtime) {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
        super.promise { callback =>
          register(callback); registered.countDown()
        }
    }
    given Close[IO, Unit] with {
      def onClose(value: Unit): IO[Unit] = IO.never
      def close(value: Unit): IO[Unit]   = IO.unit
    }
    val pool     = new AsyncResourcePool[IO, Unit]("lazy-disposal", IO.unit, () => IO.pure(0L), 1, 1.minute)
    val observer = run(pool.onDispose().start)
    try {
      registered.await(5, TimeUnit.SECONDS) shouldBe true
      run(pool.dispose())
      run(observer.joinWithNever)
      run(pool.onDispose())
    } finally run(observer.cancel)
  }
}
