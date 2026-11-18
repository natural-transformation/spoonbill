package spoonbill.action

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.{implicits, IORuntime}
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Hub, Stream}

class HubCancellationIOSpec extends AnyFlatSpec with Matchers {
  private class Worker extends ExecutionContext {
    private val tasks                         = new ConcurrentLinkedQueue[Runnable]()
    def execute(task: Runnable): Unit         = { tasks.add(task); () }
    def reportFailure(error: Throwable): Unit = throw error
    def drain(): Unit = {
      var task  = tasks.poll()
      var count = 0
      while (task != null) {
        task.run(); count += 1; assert(count < 10000)
        task = tasks.poll()
      }
    }
  }

  "Canceling a driving hub pull" should "not grant another read while its upstream callback is still live" in {
    val worker       = new Worker
    val global       = implicits.global
    val runtime      = IORuntime(worker, ExecutionContext.global, global.scheduler, () => (), global.config)
    given Effect[IO] = new IO3Effect(runtime)
    def run[A](task: IO[A]): A = {
      val result = task.unsafeToFuture()(runtime)
      worker.drain()
      Await.result(result, 3.seconds)
    }
    var callbacks = Vector.empty[Either[Throwable, Option[Int]] => Unit]
    val input = new Stream[IO, Int] {
      def pull(): IO[Option[Int]] = IO.async_(callback => callbacks :+= callback)
      def cancel(): IO[Unit]      = IO.unit
    }
    val hub     = Hub(input)
    val first   = hub.newStreamUnsafe()
    val second  = hub.newStreamUnsafe()
    val driving = run(first.pull().start)
    callbacks.size shouldBe 1
    run(driving.cancel)
    val following = run(second.pull().start)
    try callbacks.size shouldBe 1
    finally {
      run(following.cancel)
      callbacks.foreach(_(Right(None)))
      run(first.cancel())
      run(second.cancel())
    }
  }
}
