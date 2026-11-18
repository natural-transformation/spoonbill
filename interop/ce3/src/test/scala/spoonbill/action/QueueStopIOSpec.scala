package spoonbill.action

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Queue}

class QueueStopIOSpec extends AnyFlatSpec with Matchers {
  private given Effect[IO]            = new IO3Effect(global)
  private def run[A](value: IO[A]): A = Await.result(value.timeout(5.seconds).unsafeToFuture(), 6.seconds)

  "Graceful queue stop with IO" should "settle all registered producers before draining the accepted tail to EOF" in {
    val queue = Queue[IO, Int](1)
    def registered: IO[Unit] = queue.size().flatMap {
      case 4 => IO.unit // One accepted value and three capacity waiters.
      case _ => IO.cede.flatMap(_ => registered)
    }
    // Construct while admission is open; these effects run only after the buffer fills.
    val second = queue.enqueue(2)
    val third  = queue.enqueue(3)
    val fourth = queue.enqueue(4)
    val result = run(for {
      _         <- queue.enqueue(1)
      a         <- second.start
      b         <- third.start
      c         <- fourth.start
      _         <- registered
      _         <- queue.stop()
      _         <- a.joinWithNever
      _         <- b.joinWithNever
      _         <- c.joinWithNever
      retained  <- queue.size()
      first     <- queue.stream.pull()
      end       <- queue.stream.pull()
      remaining <- queue.size()
    } yield (retained, first, end, remaining))
    result shouldBe ((1, Some(1), None, 0))
    run(queue.close())
  }
}
