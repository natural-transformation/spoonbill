package spoonbill.internal

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.{Context, Extension}
import spoonbill.effect.{Effect, Reporter}
import spoonbill.effect.syntax.*
import spoonbill.server.SessionAccessDenied

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
 *     time. A failed call is reported and does not stop the lane.
 *   - `destroy` waits for the call in flight, then calls `onDestroy` once per
 *     extension, in reverse setup order, each with a time bound.
 *
 * The session creates the runtime before the application instance, because it
 * is the instance's observer. A view without extensions has no runtime.
 *
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

  private final case class Active(name: String, handlers: Extension.Handlers[F, S, M])

  // The lock orders the observer calls against the start of delivery. It is
  // held for a few field updates only; no extension code runs under it.
  private val lock          = new Object
  private var phase         = Phase.Recording
  private var newest        = Option.empty[S] // Before delivery only the newest write matters.
  private var earlyMessages = Vector.empty[M]

  private val lane = new SerialLane[F, Notification[S, M]](deliver)
  // Newest first, so teardown runs in reverse setup order. None once teardown
  // took the list: an extension that completes later destroys itself.
  private val active = new AtomicReference(Option(List.empty[Active]))

  @volatile private var stopped       = false
  @volatile private var admitted      = Option.empty[S]
  @volatile private var lastDelivered = Option.empty[S]
  @volatile private var ordered       = List.empty[Active]

  // --- Observer: called by the application instance on the writing thread ---

  def state(value: S): Unit = {
    val queued = lock.synchronized {
      phase match {
        case Phase.Recording  => newest = Some(value); false
        case Phase.Delivering => lane.enqueue(Notification.State(value)); true
        case Phase.Stopped    => false
      }
    }
    if (queued) lane.wake()
  }

  def message(value: M): Unit = {
    val queued = lock.synchronized {
      phase match {
        case Phase.Recording  => earlyMessages = earlyMessages :+ value; false
        case Phase.Delivering => lane.enqueue(Notification.Message(value)); true
        case Phase.Stopped    => false
      }
    }
    if (queued) lane.wake()
  }

  // --- Setup ---

  /**
   * Sets up every extension. It fails when a `setup` fails or runs out of time,
   * and when `proceed` fails before a `setup` starts. The caller then closes
   * the view and calls [[destroy]].
   *
   * @param admittedState
   *   the state the view was accepted with. `onDestroy` receives it when no
   *   state was delivered.
   * @param proceed
   *   runs before each `setup`. It fails once the connection is gone, so the
   *   remaining extensions are not set up for a dead view.
   */
  def setup(access: Context.BaseAccess[F, S, M], admittedState: S, proceed: () => F[Unit]): F[Unit] = {
    def next(remaining: List[Extension[F, S, M]]): F[Unit] = remaining match {
      case Nil => Effect[F].unit
      case extension :: rest =>
        Effect[F]
          .delayAsync(if (stopped) Effect[F].fail[Unit](new SetupAborted) else proceed())
          .flatMap { _ =>
            bound(
              Effect[F].delayAsync(extension.setup(access)),
              // A result after the bound belongs to a failed attach.
              (handlers: Extension.Handlers[F, S, M]) => destroyOne(Active(extension.name, handlers))
            ).recoverF { case error =>
              report(extension.name, "setup", error)
              Effect[F].fail(error)
            }
          }
          .flatMap(handlers => register(Active(extension.name, handlers)))
          .flatMap(_ => next(rest))
    }
    Effect[F].delay { admitted = Some(admittedState) }.flatMap(_ => next(extensions))
  }

  private def register(entry: Active): F[Unit] = {
    @tailrec def add(): Boolean = active.get() match {
      case current @ Some(entries) => if (active.compareAndSet(current, Some(entry :: entries))) true else add()
      case None                    => false
    }
    // Teardown already took the list, so nobody else will destroy this one.
    if (add()) Effect[F].unit else destroyOne(entry)
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
          newest.orElse(admitted).foreach(attach => lane.enqueue(Notification.State(attach)))
          earlyMessages.foreach(message => lane.enqueue(Notification.Message(message)))
          newest = None
          earlyMessages = Vector.empty
          phase = Phase.Delivering
          true
        case _ => false // Stopped before delivery could start.
      }
    }
    if (started) lane.wake()
  }

  private def deliver(notification: Notification[S, M]): F[Unit] =
    if (stopped) Effect[F].unit
    else
      notification match {
        case Notification.State(value) =>
          admit(_.state(value)).flatMap {
            case false => Effect[F].unit
            case true =>
              lastDelivered = Some(value)
              each("onState")(_.onState(value))
          }
        case Notification.Message(value) =>
          admit(_.message(value)).flatMap {
            case false => Effect[F].unit
            case true  => each("onMessage")(_.onMessage(value))
          }
      }

  private val allowed: F[Boolean] = Effect[F].pure(true)

  /** A legacy session has no checks, and then this costs nothing. */
  private def admit(check: Checks[F, S, M] => F[Unit]): F[Boolean] = checks match {
    case None => allowed
    case Some(guarded) =>
      Effect[F].delayAsync(check(guarded)).map(_ => true).recover { case _ =>
        // The check denies the view itself. No further callback may start.
        stop()
        false
      }
  }

  /**
   * Calls every extension in setup order. The next call waits for the previous
   * one.
   */
  private def each(callback: String)(call: Extension.Handlers[F, S, M] => F[Unit]): F[Unit] = {
    def next(remaining: List[Active]): F[Unit] = remaining match {
      case Nil           => Effect[F].unit
      case entry :: Nil  => isolated(entry.name, callback)(call(entry.handlers))
      case entry :: rest => isolated(entry.name, callback)(call(entry.handlers)).flatMap(_ => next(rest))
    }
    next(ordered)
  }

  /**
   * A failed effect and a thrown exception are treated the same: reported, not
   * passed on.
   */
  private def isolated(name: String, callback: String)(call: => F[Unit]): F[Unit] =
    Effect[F].delayAsync(call).recover { case error => report(name, callback, error) }

  private def report(name: String, callback: String, error: Throwable): Unit = error match {
    // Using the access of a view that is closing is expected to fail.
    case _: SessionAccessDenied if stopped => ()
    case _: SetupAborted                   => ()
    case _ =>
      try reporter.error(s"Extension $name: $callback failed", error)
      catch { case NonFatal(_) => () }
  }

  // --- Teardown ---

  /**
   * Stops the lane: no new delivery starts. The call in flight is not
   * interrupted. Safe to call more than once.
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
      try reporter.error("An extension callback was still running at teardown", error)
      catch { case NonFatal(_) => () }
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

  private def destroyOne(entry: Active): F[Unit] =
    isolated(entry.name, "onDestroy") {
      bound(Effect[F].delayAsync(entry.handlers.onDestroy(lastState)), (_: Unit) => Effect[F].unit)
    }

  /**
   * The last delivered state, or the admitted state. Both passed a check
   * earlier.
   */
  private def lastState: S =
    lastDelivered.orElse(admitted).getOrElse(throw new IllegalStateException("No admitted state"))

  /** Notifications that wait in the lane. For tests and diagnostics. */
  private[spoonbill] def pending: Int = lane.size
}

private[spoonbill] object ExtensionRuntime {

  /** Runs an operation with the lifecycle time bound. */
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

  /** The runtime was stopped before every extension was set up. */
  final class SetupAborted extends RuntimeException("Extension setup was aborted because the view is closing")

  /**
   * A `setup` or `onDestroy` call, or the wait for a delivery, ran out of time.
   */
  final class TimedOut extends RuntimeException("An extension call did not complete within its time bound")
}
