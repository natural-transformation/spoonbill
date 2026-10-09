package spoonbill.zio.http

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import zio.{Duration, Exit, Promise, Ref, Runtime, ZIO, Unsafe}

final class WebSocketSetupSpec extends AnyFlatSpec with Matchers {
  private def check[A](program: ZIO[Any, Throwable, A], expected: A): Unit = {
    val bounded = program.timeoutFail(new RuntimeException("Setup lifecycle did not finish"))(Duration.fromSeconds(3))
    val result = Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(bounded) }
    result shouldBe Exit.succeed(expected)
  }

  "WebSocket setup deadline" should "release a prepared response which is never attached" in {
    val program = for {
      inputClosed <- Ref.make(0)
      released <- Promise.make[Nothing, Unit]
      setup <- WebSocketSetup.make(inputClosed.update(_ + 1), 50.millis)
      admitted <- setup.prepare(() => released.succeed(()).unit)
      _ <- released.await
      attached <- setup.attach
      closed <- inputClosed.get
    } yield (admitted, attached, closed)
    check(program, (true, false, 1))
  }

  it should "dispose of a response acquired after setup expired" in {
    val program = for {
      inputClosed <- Promise.make[Nothing, Unit]
      releases <- Ref.make(0)
      setup <- WebSocketSetup.make(inputClosed.succeed(()).unit, 50.millis)
      _ <- inputClosed.await
      admitted <- setup.prepare(() => releases.update(_ + 1))
      attached <- setup.attach
      _ <- setup.abandon
      released <- releases.get
    } yield (admitted, attached, released)
    check(program, (false, false, 1))
  }

  it should "transfer ownership to the handler without releasing its active session" in {
    val program = for {
      inputClosed <- Ref.make(0)
      releases <- Ref.make(0)
      setup <- WebSocketSetup.make(inputClosed.update(_ + 1), 10.seconds)
      admitted <- setup.prepare(() => releases.update(_ + 1))
      attached <- setup.attach
      _ <- setup.abandon
      again <- setup.attach
      closed <- inputClosed.get
      released <- releases.get
    } yield (admitted, attached, again, closed, released)
    check(program, (true, true, false, 0, 0))
  }
}
