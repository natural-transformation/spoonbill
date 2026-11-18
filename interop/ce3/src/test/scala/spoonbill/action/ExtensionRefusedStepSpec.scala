package spoonbill.action

import _root_.cats.effect.{Deferred, IO}
import _root_.cats.effect.unsafe.{implicits, IORuntime}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, RejectedExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.Extension
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Reporter}
import spoonbill.internal.ExtensionRuntime

class ExtensionRefusedStepSpec extends AnyFlatSpec with Matchers {
  private val global = implicits.global
  private class Worker extends ExecutionContext {
    val tasks  = new ConcurrentLinkedQueue[Runnable]()
    val refuse = new AtomicReference[Option[() => Unit]](None)
    def execute(task: Runnable): Unit = refuse.getAndSet(None) match {
      case Some(before) => before(); throw new RejectedExecutionException("one refused fiber")
      case None         => tasks.add(task); ()
    }
    def reportFailure(error: Throwable): Unit = throw error
    def one(): Unit                           = Option(tasks.poll()).getOrElse(fail("Expected a task")).run()
    def drain(): Unit = {
      var count = 0
      while (!tasks.isEmpty) { one(); count += 1; assert(count < 1000) }
    }
  }

  private class Fixture {
    val lane               = new Worker
    val compute            = new Worker
    val ioRuntime          = IORuntime(compute, ExecutionContext.global, global.scheduler, () => (), global.config)
    given Effect[IO]       = new IO3Effect(ioRuntime)
    given ExecutionContext = lane
    val events             = new ConcurrentLinkedQueue[String]()
    val reports            = new ConcurrentLinkedQueue[String]()
    val pending            = new ConcurrentLinkedQueue[Deferred[IO, Unit]]()
    val bound = new ExtensionRuntime.Bound[IO] {
      def apply[A](operation: => IO[A], late: A => IO[Unit]): IO[A] =
        Deferred[IO, Unit].flatMap { expired =>
          IO { pending.add(expired); () } *> IO.race(IO.defer(operation), expired.get).flatMap {
            case Left(value) => IO.pure(value)
            case Right(_)    => IO.raiseError(new ExtensionRuntime.TimedOut)
          }
        }
    }
    val reporter = new Reporter {
      def error(message: String, cause: Throwable): Unit       = { reports.add(message); () }
      def error(message: String): Unit                         = { reports.add(message); () }
      def warning(message: String, cause: Throwable): Unit     = ()
      def warning(message: String): Unit                       = ()
      def info(message: String): Unit                          = ()
      def debug(message: String): Unit                         = ()
      def debug(message: String, a: Any): Unit                 = ()
      def debug(message: String, a: Any, b: Any): Unit         = ()
      def debug(message: String, a: Any, b: Any, c: Any): Unit = ()
    }
    def record(name: String) = Extension[IO, Int, String](name)(_ =>
      IO.pure(
        Extension.Handlers[IO, Int, String](
          onState = value => IO { events.add(s"$name.state($value)"); () },
          onDestroy = value => IO { events.add(s"$name.destroy($value)"); () }
        )
      )
    )
    val runtime              = new ExtensionRuntime[IO, Int, String](List(record("a"), record("b")), reporter, bound, None)
    def run[A](io: IO[A]): A = io.unsafeRunSync()(global)
    def drain(): Unit = {
      var count = 0
      while (!lane.tasks.isEmpty || !compute.tasks.isEmpty) {
        lane.drain(); compute.drain(); count += 1; assert(count < 1000)
      }
    }
    run(runtime.setup(null, 7, () => IO.unit))
    run(runtime.start())
    drain()
    events.clear()

    def beforeCall(): Unit = {
      runtime.state(9)
      lane.one()      // take the check and submit its effect
      compute.drain() // settle the check, scheduling the call's lane task
    }
  }

  "A refused extension step" should "report its name and keep subsequent calls in order" in {
    val f = new Fixture
    f.beforeCall()
    f.compute.refuse.set(Some(() => ()))
    f.drain()
    f.reports.asScala.toVector shouldBe Vector("Extension a: onState failed")
    f.events.asScala.toVector shouldBe Vector("b.state(9)")
    f.runtime.state(10)
    f.drain()
    f.events.asScala.toVector shouldBe Vector("b.state(9)", "a.state(10)", "b.state(10)")
    f.runtime.stop()
  }

  for (takeFirst <- List(false, true)) {
    it should s"retain the refused call's identity across teardown (take first: $takeFirst)" in {
      val f = new Fixture
      f.beforeCall()
      val submitting = new CountDownLatch(1)
      val release    = new CountDownLatch(1)
      f.compute.refuse.set(Some { () =>
        submitting.countDown(); assert(release.await(10, TimeUnit.SECONDS))
      })
      val thread = new Thread(() => f.lane.one())
      thread.start()
      submitting.await(5, TimeUnit.SECONDS) shouldBe true
      f.runtime.stop()
      val destroyed = f.runtime.destroy().unsafeToFuture()(global)
      try {
        if (takeFirst) {
          // Expire only the idle wait. Subsequent bounds see a new, empty gate.
          // Wait until teardown has installed its idle bound, after setup's two bounds.
          val armedUntil = System.nanoTime() + 5.seconds.toNanos
          while (f.pending.size() < 3 && System.nanoTime() < armedUntil) Thread.`yield`()
          f.pending.size() shouldBe 3
          val gates = f.pending.asScala.toVector
          gates.foreach(gate => f.run(gate.complete(())))
          val until = System.nanoTime() + 5.seconds.toNanos
          while (!destroyed.isCompleted && System.nanoTime() < until) {
            f.drain(); Thread.`yield`()
          }
          Await.result(destroyed, 5.seconds)
          f.drain()
        }
      } finally { release.countDown(); thread.join(5000) }
      val until = System.nanoTime() + 5.seconds.toNanos
      while (!destroyed.isCompleted && System.nanoTime() < until) { f.drain(); Thread.`yield`() }
      Await.result(destroyed, 5.seconds)
      f.drain()
      f.events.asScala.toVector shouldBe Vector("b.destroy(7)", "a.destroy(7)")
      f.reports.asScala.count(_ == "Extension a: onState failed") shouldBe 1
    }
  }
}
