package spoonbill.action

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Stream}

class StreamConsumedIOSpec extends AnyFlatSpec with Matchers {
  "A reusable consumption signal" should "settle all observers and replay for a late observer" in {
    val registered = new CountDownLatch(2)
    given Effect[IO] = new IO3Effect(global) {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
        super.promise { callback =>
          register(callback); registered.countDown()
        }
    }
    val (consumed, downstream) = Stream.empty[IO, Int].handleConsumed
    val first                  = consumed.unsafeToFuture()
    val second                 = consumed.unsafeToFuture()
    registered.await(3, TimeUnit.SECONDS) shouldBe true
    Await.result(downstream.pull().unsafeToFuture(), 3.seconds) shouldBe None
    Await.result(first, 3.seconds) shouldBe ()
    Await.result(second, 3.seconds) shouldBe ()
    Await.result(consumed.unsafeToFuture(), 3.seconds) shouldBe ()
  }
}
