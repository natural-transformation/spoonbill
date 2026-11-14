package spoonbill.internal

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import spoonbill.Extension
import spoonbill.effect.Effect
import spoonbill.internal.TimerTestSupport.{Reports, Worker}

/**
 * A runtime over `Int` states and `String` messages.
 *
 * A manual worker runs the lane, so a test decides when delivery happens. A
 * manual bound expires only when a test says so.
 */
private[internal] final class ExtensionRuntimeFixture(
  checks: Option[ExtensionRuntime.Checks[Future, Int, String]] = None
)(build: ExtensionRuntimeFixture => List[Extension[Future, Int, String]]) {

  private given Effect[Future]   = Effect.futureEffect
  val worker                     = new Worker
  private given ExecutionContext = worker
  val reports                    = new Reports
  val bound                      = new ExtensionRuntimeFixture.ManualBound

  private val events = new AtomicReference(Vector.empty[String])

  def log(text: String): Unit = { events.updateAndGet(_ :+ text); () }

  /** Runs the queued work, then returns what happened since the last call. */
  def taken(): Vector[String] = {
    worker.drain()
    events.getAndSet(Vector.empty)
  }

  /** An extension that logs each call as `name.callback(argument)`. */
  def recorder(
    name: String,
    onSetup: () => Future[Unit] = () => Future.unit,
    onState: Int => Future[Unit] = _ => Future.unit,
    onMessage: String => Future[Unit] = _ => Future.unit,
    onDestroy: Int => Future[Unit] = _ => Future.unit
  ): Extension[Future, Int, String] =
    Extension[Future, Int, String](name) { _ =>
      log(s"$name.setup")
      // Complete on the calling thread, so a finished setup needs no worker turn.
      onSetup().map { _ =>
        Extension.Handlers[Future, Int, String](
          onState = state => { log(s"$name.onState($state)"); onState(state) },
          onMessage = message => { log(s"$name.onMessage($message)"); onMessage(message) },
          onDestroy = last => { log(s"$name.onDestroy($last)"); onDestroy(last) }
        )
      }(ExecutionContext.parasitic)
    }

  val runtime = new ExtensionRuntime[Future, Int, String](build(this), reports, bound, checks)

  def result[A](future: Future[A]): A = Await.result(future, 5.seconds)

  def setup(admitted: Int = 0, proceed: () => Future[Unit] = () => Future.unit): Future[Unit] =
    runtime.setup(null, admitted, proceed)

  /**
   * Sets every extension up with the given admitted state, then starts
   * delivery.
   */
  def attach(admitted: Int = 0): Unit = {
    result(setup(admitted))
    result(runtime.start())
  }

  /** The reported failures: message and cause. */
  def errors: Vector[(String, Throwable)] = reports.errors
}

private[internal] object ExtensionRuntimeFixture {

  /**
   * A bound that never expires by itself. `expire` ends every pending call with
   * a timeout. A result that arrives later goes to the late hook, as in the
   * session's bound.
   */
  final class ManualBound extends ExtensionRuntime.Bound[Future] {
    private val pending = new AtomicReference(List.empty[Promise[Unit]])

    def apply[A](operation: => Future[A], late: A => Future[Unit]): Future[A] = {
      val deadline = Promise[Unit]()
      pending.updateAndGet(deadline :: _)
      val result = Promise[A]()
      val running =
        try operation
        catch { case NonFatal(error) => Future.failed(error) }
      running.onComplete { outcome =>
        if (!result.tryComplete(outcome)) outcome.foreach(value => late(value))
      }(ExecutionContext.parasitic)
      deadline.future.foreach(_ => result.tryFailure(new ExtensionRuntime.TimedOut))(ExecutionContext.parasitic)
      result.future
    }

    def expire(): Unit = pending.getAndSet(Nil).foreach(_.trySuccess(()))
  }
}
