package spoonbill.internal

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.Extension
import spoonbill.effect.{AsyncTable, Effect, Reporter}

/**
 * The same extension contract under Future-like and lazy effect adapters.
 *
 * A strict effect starts a `setup` when the effect value is built. A lazy
 * effect starts it when the value is run. The runtime must order setup,
 * delivery and teardown the same way for both.
 */
object ExtensionAdapterChecks {

  private class Reports extends Reporter {
    val failures                                                      = new LinkedBlockingQueue[String]()
    def error(message: String, error: Throwable): Unit                = { failures.add(message); () }
    def error(message: String): Unit                                  = { failures.add(message); () }
    def warning(message: String, error: Throwable): Unit              = ()
    def warning(message: String): Unit                                = ()
    def info(message: String): Unit                                   = ()
    def debug(message: String): Unit                                  = ()
    def debug(message: String, arg1: Any): Unit                       = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  }

  private def run[F[_]: Effect, A](effect: F[A]): A = Await.result(Effect[F].toFuture(effect), 10.seconds)

  private def unbounded[F[_]]: ExtensionRuntime.Bound[F] = new ExtensionRuntime.Bound[F] {
    def apply[A](operation: => F[A], late: A => F[Unit]): F[A] = operation
  }

  /**
   * Runs the operation on its own, as the session's bound does. So the caller
   * only waits for it, and cancelling the caller does not cancel the operation.
   */
  private def detached[F[_]: Effect]: ExtensionRuntime.Bound[F] = new ExtensionRuntime.Bound[F] {
    def apply[A](operation: => F[A], late: A => F[Unit]): F[A] =
      Effect[F].promise[A](callback => Effect[F].runAsync(Effect[F].delayAsync(operation))(callback))
  }

  /** What the extensions did, in the order it happened. */
  private final class Events {
    private val queue                            = new LinkedBlockingQueue[String]()
    def add[F[_]: Effect](text: String): F[Unit] = Effect[F].delay { queue.add(text); () }
    def expect(expected: String*): Unit = expected.foreach { text =>
      val actual = queue.poll(10, TimeUnit.SECONDS)
      assert(actual == text, s"Expected $text but got $actual")
    }
    def expectNothing(): Unit = {
      val actual = queue.poll(200, TimeUnit.MILLISECONDS)
      assert(actual == null, s"Expected nothing but got $actual")
    }
  }

  private def recorder[F[_]: Effect](
    events: Events,
    name: String,
    failOn: Option[Int] = None
  ): Extension[F, Int, String] = {
    val F = Effect[F]
    Extension[F, Int, String](name) { _ =>
      F.map(events.add[F](s"$name.setup")) { _ =>
        Extension.Handlers[F, Int, String](
          onState = state =>
            F.flatMap(events.add[F](s"$name.onState($state)")) { _ =>
              if (failOn.contains(state)) F.fail[Unit](new IllegalStateException("synthetic")) else F.unit
            },
          onMessage = message => events.add[F](s"$name.onMessage($message)"),
          onDestroy = last => events.add[F](s"$name.onDestroy($last)")
        )
      }
    }
  }

  /**
   * The attach state, ordered delivery, isolation of a failing handler, and
   * reverse teardown.
   */
  def contract[F[_]: Effect]()(implicit ec: ExecutionContext): Unit = {
    val F       = Effect[F]
    val events  = new Events
    val reports = new Reports
    val runtime = new ExtensionRuntime[F, Int, String](
      List(recorder[F](events, "a", failOn = Some(2)), recorder[F](events, "b")),
      reports,
      unbounded[F],
      None
    )
    run(runtime.setup(null, 0, () => F.unit))
    events.expect("a.setup", "b.setup")
    // Written before delivery starts: only the newest state is delivered.
    runtime.state(1)
    runtime.state(5)
    runtime.message("early")
    run(runtime.start())
    events.expect("a.onState(5)", "b.onState(5)", "a.onMessage(early)", "b.onMessage(early)")
    // The first extension fails on 2. The second one still receives it.
    runtime.state(2)
    runtime.state(3)
    runtime.message("late")
    events.expect(
      "a.onState(2)",
      "b.onState(2)",
      "a.onState(3)",
      "b.onState(3)",
      "a.onMessage(late)",
      "b.onMessage(late)"
    )
    assert(reports.failures.poll(10, TimeUnit.SECONDS) == "Extension a: onState failed")
    run(runtime.destroy())
    events.expect("b.onDestroy(3)", "a.onDestroy(3)")
    events.expectNothing()
    assert(reports.failures.isEmpty, "A failure was reported more than once")
  }

  /** The second `setup` starts only when the first one has completed. */
  def sequentialSetup[F[_]: Effect]()(implicit ec: ExecutionContext): Unit = {
    val F      = Effect[F]
    val events = new Events
    val gate   = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val first = Extension[F, Int, String]("first") { _ =>
      F.flatMap(events.add[F]("first.setup started")) { _ =>
        F.map(gate.get(()))(_ => Extension.Handlers[F, Int, String]())
      }
    }
    val runtime =
      new ExtensionRuntime[F, Int, String](List(first, recorder[F](events, "second")), new Reports, unbounded[F], None)
    val done = F.toFuture(runtime.setup(null, 0, () => F.unit))
    events.expect("first.setup started")
    events.expectNothing()
    run(gate.put((), ()))
    Await.result(done, 10.seconds)
    events.expect("second.setup")
  }

  /**
   * A failed `setup` stops the ones after it, and the rollback destroys the
   * ones before it.
   */
  def failedSetup[F[_]: Effect]()(implicit ec: ExecutionContext): Unit = {
    val F       = Effect[F]
    val events  = new Events
    val reports = new Reports
    val failing = Extension[F, Int, String]("failing") { _ =>
      F.flatMap(events.add[F]("failing.setup"))(_ => F.fail(new IllegalStateException("synthetic")))
    }
    val runtime = new ExtensionRuntime[F, Int, String](
      List(recorder[F](events, "before"), failing, recorder[F](events, "after")),
      reports,
      unbounded[F],
      None
    )
    val outcome = Await.ready(F.toFuture(runtime.setup(null, 7, () => F.unit)), 10.seconds).value.get
    assert(outcome.isFailure, "A failed setup did not fail the attach")
    events.expect("before.setup", "failing.setup")
    assert(reports.failures.poll(10, TimeUnit.SECONDS) == "Extension failing: setup failed")
    run(runtime.destroy())
    events.expect("before.onDestroy(7)")
    events.expectNothing()
  }

  /**
   * A handler that ends without a result: interrupted with ZIO, cancelled with
   * cats-effect. It is reported like a failed handler, and the extensions after
   * it still get the notification.
   *
   * @param interrupted
   *   an effect that interrupts or cancels itself
   */
  def interruptedHandler[F[_]: Effect](interrupted: => F[Unit])(implicit ec: ExecutionContext): Unit = {
    val F       = Effect[F]
    val events  = new Events
    val reports = new Reports
    val leaving = Extension[F, Int, String]("leaving") { _ =>
      F.pure(
        Extension.Handlers[F, Int, String](
          onMessage = message => F.flatMap(events.add[F](s"leaving.onMessage($message)"))(_ => interrupted)
        )
      )
    }
    val runtime =
      new ExtensionRuntime[F, Int, String](List(leaving, recorder[F](events, "after")), reports, unbounded[F], None)
    run(runtime.setup(null, 0, () => F.unit))
    run(runtime.start())
    events.expect("after.setup", "after.onState(0)")
    runtime.message("first")
    runtime.message("second")
    events.expect(
      "leaving.onMessage(first)",
      "after.onMessage(first)",
      "leaving.onMessage(second)",
      "after.onMessage(second)"
    )
    assert(reports.failures.poll(10, TimeUnit.SECONDS) == "Extension leaving: onMessage failed")
    assert(reports.failures.poll(10, TimeUnit.SECONDS) == "Extension leaving: onMessage failed")
    run(runtime.destroy())
    events.expect("after.onDestroy(0)")
  }

  /**
   * The attach is cancelled while a `setup` runs. The handlers of that `setup`
   * still reach the runtime, so the teardown destroys them.
   *
   * @param start
   *   starts an effect and returns the function that cancels it
   */
  def cancelledAttach[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F      = Effect[F]
    val events = new Events
    val gate   = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val slow = Extension[F, Int, String]("slow") { _ =>
      F.flatMap(events.add[F]("slow.setup started")) { _ =>
        F.map(gate.get(())) { _ =>
          Extension.Handlers[F, Int, String](onDestroy = last => events.add[F](s"slow.onDestroy($last)"))
        }
      }
    }
    val runtime = new ExtensionRuntime[F, Int, String](List(slow), new Reports, detached[F], None)
    val cancel  = start(runtime.setup(null, 4, () => F.unit))
    events.expect("slow.setup started")
    cancel()
    // The setup completes after the attach has gone away.
    run(gate.put((), ()))
    run(runtime.destroy())
    events.expect("slow.onDestroy(4)")
    events.expectNothing()
  }
}
