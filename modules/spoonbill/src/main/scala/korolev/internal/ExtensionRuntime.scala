package spoonbill.internal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.{Context, Extension}
import spoonbill.effect.{Effect, Reporter}
import spoonbill.effect.syntax.*
import spoonbill.server.SessionAccessDenied
import spoonbill.snapshot.ViewSnapshotException

/**
 * Owns the extensions of one attached view: setup, delivery and teardown.
 *
 * The contract, for legacy and guarded sessions alike:
 *
 *   - `setup` runs for one extension at a time, in registration order, with a
 *     time bound. The runtime keeps the handlers from the moment a `setup`
 *     completes.
 *   - Before delivery starts, only the newest written state is kept. It becomes
 *     the first `onState` call. Messages are kept in publish order.
 *   - One lane delivers every notification to every extension, one call at a
 *     time. A call that does not succeed is reported and does not stop the
 *     lane. That covers a failed effect, a thrown exception and an interrupted
 *     or cancelled effect.
 *   - A call starts in a task of the execution context: never inside the call
 *     that announces a write, and never on the thread that completed the
 *     previous call.
 *   - `destroy` waits for the call in flight, then calls `onDestroy` once per
 *     extension, in reverse setup order, each with a time bound.
 *
 * The session creates the runtime before the application instance, because it
 * is the instance's observer. A view without extensions has no runtime.
 *
 * @param bound
 *   the time bound of `setup`, `onDestroy` and the wait for the call in flight.
 *   It fails an expired call with [[ExtensionRuntime.TimedOut]].
 * @param checks
 *   the guarded checks that run before a delivery. A failed check stops the
 *   lane; denying the view is the job of the check itself.
 */
private[spoonbill] final class ExtensionRuntime[F[_]: Effect, S, M](
  extensions: List[Extension[F, S, M]],
  reporter: Reporter,
  bound: ExtensionRuntime.Bound[F],
  checks: Option[ExtensionRuntime.Checks[F, S, M]]
)(implicit ec: ExecutionContext)
    extends ApplicationInstance.Observer[S, M] {

  import ExtensionRuntime.*

  private final class Active(val name: String, val handlers: Extension.Handlers[F, S, M]) {
    // The last state that was passed to this extension's onState. It is set
    // when the call is made, not when the state passes its check: a view can
    // end between the two, and then this extension never got that state.
    @volatile var received = Option.empty[S]
  }

  // What the lane handles. One notification is a check followed by one call
  // per extension, so every call settles on its own and none can hold back or
  // skip the calls after it.
  private sealed trait Step
  private final class Delivery(val notification: Notification[S, M]) extends Step {
    // Set when the check has passed. Without it no extension is called.
    @volatile var admitted = false
  }
  private final case class Call(delivery: Delivery, entry: Active) extends Step

  // The lock orders the observer calls against the start of delivery. It is
  // held for a few field updates only; no extension code runs under it.
  private val lock          = new Object
  private var phase         = Phase.Recording
  private var newest        = Option.empty[S] // Before delivery only the newest write matters.
  private var earlyMessages = Vector.empty[M]

  private val lane = new SerialLane[F, Step](handle, failed, unscheduled)
  // One report is enough to explain why delivery stopped.
  private val unscheduledReported = new AtomicBoolean(false)
  // Newest first, so teardown runs in reverse setup order. None once teardown
  // took the list: an extension that completes later destroys itself.
  private val active = new AtomicReference(Option(List.empty[Active]))

  @volatile private var stopped  = false
  @volatile private var admitted = Option.empty[S]
  @volatile private var ordered  = List.empty[Active]

  // --- Observer: called by the application instance on the writing thread ---

  def state(value: S): Unit = {
    val queued = lock.synchronized {
      phase match {
        case Phase.Recording  => newest = Some(value); false
        case Phase.Delivering => enqueue(Notification.State(value)); true
        case Phase.Stopped    => false
      }
    }
    if (queued) lane.wake()
  }

  def message(value: M): Unit = {
    val queued = lock.synchronized {
      phase match {
        case Phase.Recording  => earlyMessages = earlyMessages :+ value; false
        case Phase.Delivering => enqueue(Notification.Message(value)); true
        case Phase.Stopped    => false
      }
    }
    if (queued) lane.wake()
  }

  /** Call it under the lock, so the steps of two notifications do not mix. */
  private def enqueue(notification: Notification[S, M]): Unit = {
    val delivery = new Delivery(notification)
    lane.enqueue(delivery)
    ordered.foreach(entry => lane.enqueue(Call(delivery, entry)))
  }

  // --- Setup ---

  /**
   * Sets up every extension. It fails when a `setup` fails or runs out of time,
   * when `proceed` fails, and when the runtime is stopped meanwhile. The caller
   * then closes the view and calls [[destroy]].
   *
   * @param admittedState
   *   the state the view was accepted with. `onDestroy` receives it when no
   *   state was delivered.
   * @param proceed
   *   runs before each `setup`, and once after the last one. It fails once the
   *   connection is gone, so the remaining extensions are not set up and
   *   delivery does not start for a dead view.
   */
  def setup(access: Context.BaseAccess[F, S, M], admittedState: S, proceed: () => F[Unit]): F[Unit] = {
    def live(): F[Unit] =
      Effect[F].delayAsync(if (stopped) Effect[F].fail[Unit](new SetupAborted) else proceed())
    def next(remaining: List[Extension[F, S, M]]): F[Unit] = remaining match {
      case Nil => live()
      case extension :: rest =>
        live().flatMap { _ =>
          // The handlers are handed over inside the bounded call. So a result
          // is never lost: not when it arrives after the bound, and not when
          // the waiting attach is cancelled.
          bound(
            Effect[F]
              .delayAsync(extension.setup(access))
              .flatMap(handlers => register(new Active(extension.name, handlers))),
            (_: Boolean) => Effect[F].unit
          ).recoverF { case error =>
            report(extension.name, Setup, error)
            Effect[F].fail(error)
          }
        }.flatMap {
          case true => next(rest)
          // Teardown ran during this setup and has destroyed its result.
          case false => Effect[F].fail(new SetupAborted)
        }
    }
    Effect[F].delay { admitted = Some(admittedState) }.flatMap(_ => next(extensions))
  }

  /**
   * Keeps the handlers for teardown. False when teardown already took the list:
   * nobody else would destroy this extension, so it is destroyed here. The view
   * is closed by then.
   */
  private def register(entry: Active): F[Boolean] = {
    @tailrec def add(): Boolean = active.get() match {
      case current @ Some(entries) => if (active.compareAndSet(current, Some(entry :: entries))) true else add()
      case None                    => false
    }
    if (add()) Effect[F].pure(true) else destroyOne(entry).map(_ => false)
  }

  // --- Delivery ---

  /**
   * Starts delivery, after every `setup` has completed. The first `onState`
   * call carries the newest state written so far, or the admitted state.
   * Messages that were published so far follow it.
   */
  def start(): F[Unit] = Effect[F].delay {
    ordered = active.get().fold(List.empty[Active])(_.reverse)
    val started = lock.synchronized {
      phase match {
        case Phase.Recording =>
          newest.orElse(admitted).foreach(attach => enqueue(Notification.State(attach)))
          earlyMessages.foreach(message => enqueue(Notification.Message(message)))
          newest = None
          earlyMessages = Vector.empty
          phase = Phase.Delivering
          true
        case _ => false // Stopped before delivery could start.
      }
    }
    if (started) lane.wake()
  }

  /** One step of the lane. A step that fails goes to [[failed]]. */
  private def handle(step: Step): F[Unit] = step match {
    case delivery: Delivery =>
      if (stopped) Effect[F].unit
      else
        checks match {
          // A legacy session has no checks, and then this costs nothing.
          case None =>
            delivery.admitted = true
            Effect[F].unit
          case Some(guarded) =>
            val check = delivery.notification match {
              case Notification.State(value)   => guarded.state(value)
              case Notification.Message(value) => guarded.message(value)
            }
            check.map(_ => delivery.admitted = true)
        }
    case Call(delivery, entry) =>
      // Nothing starts once teardown began, also not for the extensions that
      // have not seen this notification yet.
      if (stopped || !delivery.admitted) Effect[F].unit
      else
        delivery.notification match {
          case Notification.State(value) =>
            entry.received = Some(value)
            entry.handlers.onState(value)
          case Notification.Message(value) => entry.handlers.onMessage(value)
        }
  }

  /**
   * A step that did not succeed. A failed effect, a thrown exception and an
   * interrupted or cancelled effect are treated the same.
   */
  private def failed(step: Step, error: Throwable): Unit = step match {
    case _: Delivery =>
      // The check denies the view itself. No further callback may start.
      stop()
      error match {
        case _: SessionAccessDenied => ()
        // A check that fails for another reason must not end delivery in silence.
        case _ => reportError("Extension delivery was stopped: its check failed", error)
      }
    case Call(delivery, entry) =>
      val callback = delivery.notification match {
        case _: Notification.State[?]   => OnState
        case _: Notification.Message[?] => OnMessage
      }
      report(entry.name, callback, error)
  }

  /** The execution context refused the lane. The notifications stay queued. */
  private def unscheduled(error: Throwable): Unit =
    if (unscheduledReported.compareAndSet(false, true))
      reportError("Extension delivery could not be scheduled", error)

  private def report(name: String, callback: String, error: Throwable): Unit = error match {
    // A handler that was running when the view closed used a closed access.
    // That is expected. A failed onDestroy is always reported.
    case _: SessionAccessDenied if stopped && (callback == OnState || callback == OnMessage) => ()
    case _: SetupAborted                                                                     => ()
    case _                                                                                   => reportError(s"Extension $name: $callback failed", error)
  }

  /**
   * Every report of the runtime passes through here. A snapshot rejection can
   * carry private details of its provider, so only its error code is reported.
   */
  private def reportError(message: String, error: Throwable): Unit =
    try reporter.error(message, ViewSnapshotException.reportable(error))
    catch { case NonFatal(_) => () }

  // --- Teardown ---

  /**
   * Stops the lane: no new call starts. The call in flight is not interrupted.
   * Safe to call more than once.
   */
  def stop(): Unit = {
    stopped = true
    lock.synchronized {
      phase = Phase.Stopped
      newest = None
      earlyMessages = Vector.empty
    }
    lane.close()
  }

  /**
   * Destroys every extension that is set up, in reverse setup order. Call it
   * after the view is closed, so the access fails inside `onDestroy`. It waits
   * for the call in flight first, with a time bound. It runs once.
   */
  def destroy(): F[Unit] =
    Effect[F].delay(stop()).flatMap(_ => awaitDelivery()).flatMap(_ => destroyAll())

  private def awaitDelivery(): F[Unit] =
    bound(lane.idle, (_: Unit) => Effect[F].unit).recover { case error =>
      // onDestroy can now overlap with that call.
      reportError("An extension callback was still running at teardown", error)
    }

  private def destroyAll(): F[Unit] = {
    def next(remaining: List[Active]): F[Unit] = remaining match {
      case Nil           => Effect[F].unit
      case entry :: rest => destroyOne(entry).flatMap(_ => next(rest))
    }
    Effect[F].delay {
      // Drop the delivery order too, so a retired view does not keep its handlers alive.
      ordered = Nil
      active.getAndSet(None)
    }.flatMap(entries => next(entries.getOrElse(Nil)))
  }

  /**
   * A failed call and a call that runs out of time are reported, not passed on.
   */
  private def destroyOne(entry: Active): F[Unit] =
    Effect[F].delayAsync {
      bound(Effect[F].delayAsync(entry.handlers.onDestroy(lastState(entry))), (_: Unit) => Effect[F].unit)
    }.recover { case error => report(entry.name, OnDestroy, error) }

  /**
   * The last state that this extension's `onState` received, or the admitted
   * state. Both passed a check earlier.
   */
  private def lastState(entry: Active): S =
    entry.received.orElse(admitted).getOrElse(throw new IllegalStateException("No admitted state"))

  /** Steps that wait in the lane. For tests and diagnostics. */
  private[spoonbill] def pending: Int = lane.size

  /**
   * What is kept before delivery starts: at most one state, and every message.
   * For tests and diagnostics.
   */
  private[spoonbill] def recorded: Int = lock.synchronized(newest.size + earlyMessages.size)
}

private[spoonbill] object ExtensionRuntime {

  /**
   * Runs an operation with the lifecycle time bound. An operation that runs out
   * of time fails with [[TimedOut]]. It is not cancelled.
   */
  trait Bound[F[_]] {

    /**
     * @param late
     *   receives a result that arrives after the bound has expired
     */
    def apply[A](operation: => F[A], late: A => F[Unit]): F[A]
  }

  /** Guarded checks that run before a delivery. */
  trait Checks[F[_], S, M] {
    def state(value: S): F[Unit]
    def message(value: M): F[Unit]
  }

  sealed trait Notification[+S, +M]

  object Notification {
    final case class State[S](value: S)   extends Notification[S, Nothing]
    final case class Message[M](value: M) extends Notification[Nothing, M]
  }

  private enum Phase {
    case Recording, Delivering, Stopped
  }

  // The callback names in a report.
  private final val Setup     = "setup"
  private final val OnState   = "onState"
  private final val OnMessage = "onMessage"
  private final val OnDestroy = "onDestroy"

  /** The runtime was stopped before every extension was set up. */
  final class SetupAborted extends RuntimeException("Extension setup was aborted because the view is closing")

  /**
   * A `setup` or `onDestroy` call, or the wait for a delivery, ran out of time.
   */
  final class TimedOut extends RuntimeException("An extension call did not complete within its time bound")
}
