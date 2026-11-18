package spoonbill.internal

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
 *     previous call. This holds for `onState`, `onMessage` and `onDestroy`. One
 *     exception: when the execution context refuses the task, `onDestroy`
 *     starts on the thread that reached it. With `Future` the task makes the
 *     call. With cats-effect and ZIO the task starts the effect, and the
 *     runtime of the effect runs it.
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

  import ExtensionMachine.{
    Callback,
    CheckAction,
    Command,
    Event,
    Failure,
    Notification,
    NotificationAction,
    Progress,
    State,
    Step
  }
  import ExtensionRuntime.*

  private type Handlers = Extension.Handlers[F, S, M]
  private final class Active(val handlers: Handlers) {
    // Written only by the lane, read by teardown after its bounded wait.
    @volatile var received = Option.empty[S]
  }
  private final case class Held(machine: State[S, M], handlers: Vector[Active])
  private val lock           = new Object
  @volatile private var held = Held(ExtensionMachine.initial[S, M](checks.nonEmpty), Vector.empty)
  // A check and its continuation settle before the lane starts another step.
  @volatile private var progress: Progress[S, M] = None
  private val lane                               = new SerialLane[F, Step[S, M]](handle, failed, unscheduled)

  private def stopped: Boolean = ExtensionMachine.stopped(held.machine)

  /**
   * Called under the admission monitor, for startup and subsequent
   * notifications alike.
   */
  private def enqueue(notification: Notification[S, M], targets: Int, names: Vector[String]): Unit = {
    val check = Step.Check(notification)
    lane.enqueue(check)
    var index = 0
    while (index < targets) {
      lane.enqueue(Step.Call(check, index, names(index)))
      index += 1
    }
  }

  private def advance(event: Event[S, M], added: Option[Active] = None): (Vector[Command[S, M]], Vector[Active]) =
    lock.synchronized {
      val Held(machine, before) = held
      val (next, commands)      = ExtensionMachine.step(machine, event)
      val handlers = next.slots match {
        case None                                    => Vector.empty
        case Some(slots) if slots.size > before.size => before ++ added
        case _                                       => before
      }
      if ((next ne machine) || (handlers ne before)) held = Held(next, handlers)
      // Admission and enqueue share one order, but delivery never takes this lock.
      commands.foreach {
        case Command.Deliver(notification, targets) =>
          enqueue(notification, targets, next.slots.getOrElse(Vector.empty))
        case _ => ()
      }
      (commands, before)
    }

  private def run(commands: Vector[Command[S, M]], error: Option[Throwable] = None): Unit = {
    // Begin can enqueue several buffered notifications. It gets one scheduling
    // attempt, just like one observed notification; refusal waits for the next wake.
    var woken = false
    commands.foreach {
      case Command.Deliver(_, _) =>
        if (!woken) {
          woken = true
          lane.wake()
        }
      case Command.Close        => progress = None; lane.close()
      case Command.Report(text) => error.foreach(reportError(text, _))
      case _                    => () // register, handle and destroyAll interpret the effect commands.
    }
  }

  private def feed(event: Event[S, M], error: Option[Throwable] = None): Unit = run(advance(event)._1, error)

  // --- Observer: called by the application instance on the writing thread ---

  private def notify(notification: Notification[S, M]): Unit = {
    val queued = lock.synchronized {
      val current = held
      ExtensionMachine.notificationAction(current.machine) match {
        case NotificationAction.Record =>
          held = Held(ExtensionMachine.record(current.machine, notification), current.handlers)
          false
        case NotificationAction.Deliver =>
          enqueue(
            notification,
            ExtensionMachine.targets(current.machine),
            current.machine.slots.getOrElse(Vector.empty)
          )
          true
        case NotificationAction.Ignore => false
      }
    }
    if (queued) lane.wake()
  }

  def state(value: S): Unit = notify(Notification.State(value))

  def message(value: M): Unit = notify(Notification.Message(value))

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
              .flatMap(handlers => register(extension.name, handlers)),
            (_: Boolean) => Effect[F].unit
          ).recoverF { case error =>
            report(extension.name, Callback.Setup, error)
            Effect[F].fail(error)
          }
        }.flatMap {
          case true => next(rest)
          // Teardown ran during this setup and has destroyed its result.
          case false => Effect[F].fail(new SetupAborted)
        }
    }
    Effect[F].delay(feed(Event.Admit(admittedState))).flatMap(_ => next(extensions))
  }

  /**
   * Keeps the handlers for teardown. False when teardown already took the list:
   * nobody else would destroy this extension, so it is destroyed here. The view
   * is closed by then.
   */
  private def register(name: String, handlers: Handlers): F[Boolean] =
    advance(Event.Register(name), Some(new Active(handlers)))._1.collectFirst { case Command.Reject(last) =>
      destroyOne(name, handlers, last).map(_ => false)
    }.getOrElse(Effect[F].pure(true))

  // --- Delivery ---

  /**
   * Starts delivery, after every `setup` has completed. The first `onState`
   * call carries the newest state written so far, or the admitted state.
   * Messages that were published so far follow it.
   */
  def start(): F[Unit] = Effect[F].delay(feed(Event.Begin))

  private def passed(check: Step.Check[S, M]): Unit = {
    progress = ExtensionMachine.passed(held.machine, check)
    // Close can race the publication above. Neither order retains a delivery
    // after close, including a guarded check completing after the teardown bound.
    if (stopped) progress = None
  }

  /**
   * Return only this entry, so a synchronously blocked callback keeps no
   * handler table on its stack.
   */
  private def enterCall(call: Step.Call[S, M]): Active = {
    val current = held
    if (!ExtensionMachine.mayCall(current.machine, progress, call)) null
    else {
      if (ExtensionMachine.endsDelivery(current.machine, call)) progress = None
      val entry = current.handlers(call.slot)
      call.check.notification match {
        case Notification.State(_) =>
          entry.received = ExtensionMachine.received(entry.received, call.check.notification)
        case _ => ()
      }
      entry
    }
  }

  /** One step of the lane. A step that fails goes to [[failed]]. */
  private def handle(step: Step[S, M]): F[Unit] =
    step match {
      case check: Step.Check[S, M] =>
        ExtensionMachine.checkAction(held.machine) match {
          case CheckAction.Skip => Effect[F].unit
          case CheckAction.Pass =>
            passed(check)
            Effect[F].unit
          case CheckAction.Run =>
            val guarded = checks.get
            val result = check.notification match {
              case Notification.State(value)   => guarded.state(value)
              case Notification.Message(value) => guarded.message(value)
            }
            result.map(_ => passed(check))
        }
      case call: Step.Call[S, M] =>
        val entry = enterCall(call)
        if (entry == null) Effect[F].unit
        else {
          call.check.notification match {
            case Notification.State(value)   => entry.handlers.onState(value)
            case Notification.Message(value) => entry.handlers.onMessage(value)
          }
        }
    }

  /**
   * A step that did not succeed. A failed effect, a thrown exception and an
   * interrupted or cancelled effect are treated the same.
   */
  private def failed(step: Step[S, M], error: Throwable): Unit = {
    step match {
      case call: Step.Call[S, M] if ExtensionMachine.endsDelivery(held.machine, call) => progress = None
      case _                                                                          => ()
    }
    feed(Event.Failed(step, classify(error)), Some(error))
  }

  /** The execution context refused the lane. The notifications stay queued. */
  private def unscheduled(error: Throwable): Unit =
    feed(Event.Unscheduled, Some(error))

  private def classify(error: Throwable): Failure = error match {
    case _: SessionAccessDenied => Failure.Denied
    case _: SetupAborted        => Failure.Aborted
    case _                      => Failure.Other
  }

  private def report(name: String, callback: Callback, error: Throwable): Unit =
    ExtensionMachine.reportOf(name, callback, classify(error), stopped).foreach(reportError(_, error))

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
  def stop(): Unit = feed(Event.Halt)

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
    def next(remaining: List[Command[S, M]], handlers: Vector[Active]): F[Unit] = remaining match {
      case Command.Destroy(slot, name, admitted) :: rest =>
        val entry = handlers(slot)
        destroyOne(name, entry.handlers, entry.received.orElse(admitted)).flatMap(_ => next(rest, handlers))
      case _ :: rest => next(rest, handlers)
      case Nil       => Effect[F].unit
    }
    Effect[F].delay {
      val (commands, handlers) = advance(Event.Take)
      run(commands)
      (commands, handlers)
    }.flatMap { case (commands, handlers) => next(commands.toList, handlers) }
  }

  /**
   * A failed call and a call that runs out of time are reported, not passed on.
   * The call starts in a task of the execution context, as `onState` and
   * `onMessage` do. A teardown can get here on any thread: a timer thread after
   * an idle timeout or an expired bound, or the thread that completed the
   * previous `onDestroy`.
   *
   * The bound covers the task from its submission. So the time the task waits
   * for a worker counts: when every worker is occupied, the bound still ends
   * the wait. The teardown then goes on where its effect runs. With cats-effect
   * that can need a worker again: the close watcher of a session is a fiber on
   * the execution context.
   *
   * The bound reports only that the time ran out. The call reports its own
   * failure, so that one is not lost when it arrives after the bound gave up.
   */
  private def destroyOne(name: String, handlers: Handlers, last: => Option[S]): F[Unit] = {
    // Not a value: the bound starts its time first, and submits the task then.
    def call: F[Unit] =
      inTask(handlers.onDestroy(last.getOrElse(throw new IllegalStateException("No admitted state"))))(error =>
        report(name, Callback.OnDestroy, error)
      )
    Effect[F]
      .delayAsync(bound(call, (_: Unit) => Effect[F].unit))
      .recover { case error => report(name, Callback.OnDestroy, error) }
  }

  /**
   * Starts a call in a task of the execution context, and completes when the
   * call has ended. A call that does not succeed goes to `failed`, where its
   * outcome arrives; it does not fail the result.
   *
   * The task starts the call itself. A task that only completed a promise would
   * not do: with `Future`, code behind a promise that is already complete runs
   * on the thread that asks for it, which can be the caller. With `Future` the
   * task makes the call. With cats-effect and ZIO it starts the effect, and the
   * runtime of the effect runs it.
   *
   * When the execution context refuses the task, the call starts here. What an
   * extension acquired must be released.
   */
  private def inTask(call: => F[Unit])(failed: Throwable => Unit): F[Unit] = Effect[F].promise[Unit] { callback =>
    def run(): Unit = Effect[F].runAsync(Effect[F].delayAsync(call)) { outcome =>
      outcome.left.foreach(failed)
      callback(Right(()))
    }
    try ec.execute(() => run())
    catch { case NonFatal(_) => run() }
  }

  /** Steps that wait in the lane. For tests and diagnostics. */
  private[spoonbill] def pending: Int = lane.size

  /**
   * Whether admission still needs a delivery identity; idle lanes retain none.
   */
  private[spoonbill] def retainedDelivery: Boolean = progress.nonEmpty

  /**
   * What is kept before delivery starts: at most one state, and every message.
   * For tests and diagnostics.
   */
  private[spoonbill] def recorded: Int = ExtensionMachine.recorded(held.machine)
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

  /** The runtime was stopped before every extension was set up. */
  final class SetupAborted extends RuntimeException("Extension setup was aborted because the view is closing")

  /**
   * A `setup` or `onDestroy` call, or the wait for a delivery, ran out of time.
   */
  final class TimedOut extends RuntimeException("An extension call did not complete within its time bound")
}
