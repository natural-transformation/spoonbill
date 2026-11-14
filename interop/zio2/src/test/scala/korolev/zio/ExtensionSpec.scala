package spoonbill.zio

import _root_.zio.{Duration, Promise, Runtime, Task, Unsafe, ZIO}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.DurationInt
import spoonbill.Extension
import spoonbill.effect.{Effect, Reporter}
import spoonbill.internal.{ExtensionAdapterChecks, ExtensionRuntime}

/** The shared extension checks under a lazy effect. */
class ExtensionSpec extends AnyFlatSpec {
  private given Effect[Task]     = new Zio2Effect[Any, Throwable](Runtime.default, identity, identity)
  private given ExecutionContext = ExecutionContext.global

  "ZIO extensions" should "keep the attach, delivery and teardown contract" in {
    ExtensionAdapterChecks.contract[Task]()
  }

  it should "set up one extension at a time" in {
    ExtensionAdapterChecks.sequentialSetup[Task]()
  }

  it should "stop at a failed setup and roll back" in {
    ExtensionAdapterChecks.failedSetup[Task]()
  }

  it should "report a handler that interrupts itself, and call the extensions after it" in {
    ExtensionAdapterChecks.interruptedHandler[Task](ZIO.interrupt)
  }

  it should "keep the handlers of a setup whose attach was interrupted" in {
    ExtensionAdapterChecks.cancelledAttach[Task] { attach =>
      Unsafe.unsafe { implicit unsafe =>
        val fiber = Runtime.default.unsafe.fork(attach)
        () => { Runtime.default.unsafe.run(fiber.interrupt); () }
      }
    }
  }

  // ZIO supervises the fibers that an effect forks. Each handler call runs in a
  // fiber of its own, so a child of the handler ends with the call.
  it should "keep a daemon fiber that a handler starts, and interrupt a plain child when the handler returns" in {
    val events = new LinkedBlockingQueue[String]()
    // The work tells the handler when it runs, so the handler returns after that.
    def background(name: String, running: Promise[Nothing, Unit]): Task[Unit] =
      (running.succeed(()) *> ZIO.sleep(Duration.fromMillis(300)) *> ZIO.succeed { events.add(s"$name finished"); () })
        .onInterrupt(ZIO.succeed { events.add(s"$name interrupted"); () })
    val forking = Extension.pure[Task, Int, String]("forking") { _ =>
      Extension.Handlers[Task, Int, String](
        onMessage = message =>
          Promise.make[Nothing, Unit].flatMap { running =>
            val work = background(message, running)
            (if (message == "daemon") work.forkDaemon else work.fork) *> running.await
          }
      )
    }
    val unbounded = new ExtensionRuntime.Bound[Task] {
      def apply[A](operation: => Task[A], late: A => Task[Unit]): Task[A] = operation
    }
    val runtime                       = new ExtensionRuntime[Task, Int, String](List(forking), ExtensionSpec.silent, unbounded, None)
    def run(effect: Task[Unit]): Unit = Await.result(Effect[Task].toFuture(effect), 10.seconds)
    run(runtime.setup(null, 0, () => ZIO.unit))
    run(runtime.start())
    runtime.message("child")
    assert(events.poll(10, TimeUnit.SECONDS) == "child interrupted")
    runtime.message("daemon")
    assert(events.poll(10, TimeUnit.SECONDS) == "daemon finished")
    run(runtime.destroy())
  }
}

private object ExtensionSpec {
  val silent: Reporter = new Reporter {
    def error(message: String, error: Throwable): Unit                = ()
    def error(message: String): Unit                                  = ()
    def warning(message: String, error: Throwable): Unit              = ()
    def warning(message: String): Unit                                = ()
    def info(message: String): Unit                                   = ()
    def debug(message: String): Unit                                  = ()
    def debug(message: String, arg1: Any): Unit                       = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  }
}
