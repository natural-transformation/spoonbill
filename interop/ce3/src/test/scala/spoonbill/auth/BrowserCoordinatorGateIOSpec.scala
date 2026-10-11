package spoonbill.auth

import _root_.cats.effect.{Deferred, IO, IOLocal, Ref}
import _root_.cats.effect.syntax.all.*
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all.*
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect

class BrowserCoordinatorGateIOSpec extends AnyFlatSpec with Matchers {
  "The coordinator gate" should "preserve caller IOLocal values in work and shutdown" in {
    given Effect[IO] = new IO3Effect(global)
    val checked = for {
      local <- IOLocal("default")
      gate  <- IO(new BrowserCoordinatorGate[IO](2))
      _     <- local.set("request")
      value <- gate(local.get)
      _     <- IO(value shouldBe "request")
      _     <- gate.close(local.get.flatMap(value => IO(value shouldBe "request").void))
    } yield ()
    checked.timeout(5.seconds).unsafeRunSync()
  }

  it should "resume queued work in its own fiber context rather than the granting fiber" in {
    val registrations = new CountDownLatch(2)
    given Effect[IO] = new IO3Effect(global) {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
        super.promise { callback =>
          register(callback); registrations.countDown()
        }
    }
    val checked = for {
      local   <- IOLocal("default")
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      gate    <- IO(new BrowserCoordinatorGate[IO](2))
      blocker <- (local.set("blocker") *> gate(entered.complete(()) *> release.get)).start
      _       <- entered.get
      queued  <- (local.set("queued") *> gate(local.get)).start
      _       <- IO.blocking(registrations.await(5, TimeUnit.SECONDS)).flatMap(ready => IO(ready shouldBe true))
      _       <- release.complete(())
      _       <- blocker.joinWithNever
      value   <- queued.joinWithNever
      _       <- IO(value shouldBe "queued")
      _       <- gate.close(IO.unit)
    } yield ()
    checked.timeout(10.seconds).unsafeRunSync()
  }

  it should "finish admitted ownership before observing cancellation and permit following work" in {
    given Effect[IO] = new IO3Effect(global)
    val checked = for {
      entered      <- Deferred[IO, Unit]
      release      <- Deferred[IO, Unit]
      completed    <- Ref.of[IO, Int](0)
      gate         <- IO(new BrowserCoordinatorGate[IO](2))
      worker       <- gate(entered.complete(()) *> release.get *> completed.update(_ + 1)).start
      _            <- entered.get
      cancellation <- worker.cancel.start
      _            <- release.complete(())
      _            <- cancellation.joinWithNever
      count        <- gate(completed.get)
      _            <- IO(count shouldBe 1)
      _            <- gate.close(IO.unit)
    } yield ()
    checked.timeout(5.seconds).unsafeRunSync()
  }
}
