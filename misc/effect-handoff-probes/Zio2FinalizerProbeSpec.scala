package spoonbill.zio
import _root_.zio.{Exit, Promise, Runtime, Unsafe, ZIO}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*
class Zio2FinalizerProbeSpec extends AnyFlatSpec with Matchers {
  "Native ZIO 2 finalization" should "record interruption during successful finalization" in {
    val program = for {
      entered <- Promise.make[Nothing, Exit[Nothing, Int]]
      release <- Promise.make[Nothing, Unit]
      fiber <- ZIO.succeed(7).onExit(exit => entered.succeed(exit) *> release.await).forkDaemon
      before <- entered.await
      _ <- fiber.interruptFork
      _ <- release.succeed(())
      after <- fiber.await
    } yield (before, after)
    val result = Unsafe.unsafe { implicit unsafe =>
      Await.result(Runtime.default.unsafe.runToFuture(program), 5.seconds)
    }
    info(s"Success finalizer input=${result._1}; final fiber outcome=${result._2}")
    result._1 shouldBe Exit.succeed(7)
  }
}
