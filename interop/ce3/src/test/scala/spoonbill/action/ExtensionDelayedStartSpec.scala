package spoonbill.action

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.{implicits, IORuntime}
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.Extension
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Reporter}
import spoonbill.internal.ExtensionRuntime

class ExtensionDelayedStartSpec extends AnyFlatSpec with Matchers {
  private class Worker extends ExecutionContext {
    private val tasks                         = new ConcurrentLinkedQueue[Runnable]()
    def pending: Int                          = tasks.size()
    def execute(task: Runnable): Unit         = { tasks.add(task); () }
    def reportFailure(error: Throwable): Unit = throw error
    def one(): Unit                           = Option(tasks.poll()).getOrElse(fail("Expected a task")).run()
    def drain(): Unit = {
      var turns = 0
      while (pending > 0) { one(); turns += 1; assert(turns < 1000) }
    }
  }

  "An accepted extension task" should "remain unentered when stopped between polling and effect startup" in {
    val global             = implicits.global
    val lane               = new Worker
    val compute            = new Worker
    val ioRuntime          = IORuntime(compute, ExecutionContext.global, global.scheduler, () => (), global.config)
    given Effect[IO]       = new IO3Effect(ioRuntime)
    given ExecutionContext = lane
    val events             = new ConcurrentLinkedQueue[String]()
    val errors             = new ConcurrentLinkedQueue[String]()
    val reports = new Reporter {
      def error(message: String, cause: Throwable): Unit       = { errors.add(message); () }
      def error(message: String): Unit                         = { errors.add(message); () }
      def warning(message: String, cause: Throwable): Unit     = ()
      def warning(message: String): Unit                       = ()
      def info(message: String): Unit                          = ()
      def debug(message: String): Unit                         = ()
      def debug(message: String, a: Any): Unit                 = ()
      def debug(message: String, a: Any, b: Any): Unit         = ()
      def debug(message: String, a: Any, b: Any, c: Any): Unit = ()
    }
    val bound = new ExtensionRuntime.Bound[IO] {
      def apply[A](operation: => IO[A], late: A => IO[Unit]): IO[A] = operation
    }
    def observer(name: String) = Extension[IO, Int, Unit](name)(_ =>
      IO.pure(
        Extension.Handlers[IO, Int, Unit](
          onState = value => IO { events.add(s"$name.state($value)"); () },
          onDestroy = value => IO { events.add(s"$name.destroy($value)"); () }
        )
      )
    )
    val runtime = new ExtensionRuntime[IO, Int, Unit](List(observer("a"), observer("b")), reports, bound, None)
    def drain(): Unit = {
      var turns = 0
      while (lane.pending > 0 || compute.pending > 0) {
        lane.drain(); compute.drain()
        turns += 1
        assert(turns < 1000)
      }
    }
    runtime.setup(null, 7, () => IO.unit).unsafeRunSync()(global)
    runtime.start().unsafeRunSync()(global)
    drain()
    events.clear()

    runtime.state(9)
    lane.one()      // Poll the delivery check and submit its effect.
    compute.drain() // Complete the check, making the first call ready.
    lane.one()      // Poll and successfully submit the first call, without starting it.
    compute.pending should be > 0
    events.asScala.toVector shouldBe empty
    runtime.stop()
    drain()
    events.asScala.toVector shouldBe empty

    val destroyed = runtime.destroy().unsafeToFuture()(global)
    val deadline  = System.nanoTime() + 5.seconds.toNanos
    while (!destroyed.isCompleted && System.nanoTime() < deadline) { drain(); Thread.`yield`() }
    Await.result(destroyed, 5.seconds)
    drain()
    events.asScala.toVector shouldBe Vector("b.destroy(7)", "a.destroy(7)")
    errors.asScala.toVector shouldBe empty
    runtime.destroy().unsafeRunSync()(global)
    drain()
    events.asScala.toVector shouldBe Vector("b.destroy(7)", "a.destroy(7)")
  }
}
