package spoonbill.monix
import _root_.monix.eval.Task
import _root_.monix.execution.Scheduler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Promise}
import scala.concurrent.duration.*
class MonixFinalizerProbeSpec extends AnyFlatSpec with Matchers {
  private class Worker extends ExecutionContext {
    val tasks = new ConcurrentLinkedQueue[Runnable]()
    def execute(task: Runnable): Unit = { tasks.add(task); () }
    def reportFailure(error: Throwable): Unit = throw error
    def drain(): Unit = {
      var task = tasks.poll(); var count = 0
      while(task != null) { task.run(); count += 1; assert(count < 10000); task = tasks.poll() }
    }
  }
  for (cancel <- Vector(false, true)) {
  "Native Monix finalization" should s"record successful finalization with cancellation=$cancel" in {
    val worker = new Worker
    given Scheduler = Scheduler(worker)
    def run[A](value: Task[A]): A = {
      val result = value.runToFuture
      val until = System.nanoTime() + 3.seconds.toNanos
      while (!result.isCompleted && System.nanoTime() < until) { worker.drain(); Thread.`yield`() }
      Await.result(result, 3.seconds)
    }
    val release = Promise[Unit]()
    val observed = new AtomicReference("missing")
    val fiber = run(Task.pure(7).guaranteeCase(exit => Task.eval(observed.set(exit.toString)).flatMap(_ => Task.fromFuture(release.future))).start)
    worker.drain()
    observed.get() shouldBe "Completed"
    val joined = fiber.join.runToFuture
    val canceling = if (cancel) fiber.cancel.runToFuture else scala.concurrent.Future.successful(())
    worker.drain()
    info(s"Before release: finalizer=${observed.get()}, cancel=${canceling.value}, joined=${joined.value}")
    release.success(())
    worker.drain()
    info(s"After release: finalizer=${observed.get()}, cancel=${canceling.value}, joined=${joined.value}")
    canceling.isCompleted shouldBe true
    if (!cancel) joined.value shouldBe Some(scala.util.Success(7))
    else joined.value shouldBe None
  }
  }
}
