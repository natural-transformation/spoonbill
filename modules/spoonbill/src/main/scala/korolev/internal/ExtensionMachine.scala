package spoonbill.internal

/**
 * Pure admission, delivery and teardown decisions; their owners live in the
 * interpreter.
 */
private[spoonbill] object ExtensionMachine {
  enum Notification[+S, +M] {
    case State(value: S)
    case Message(value: M)
  }
  enum Callback(val label: String) {
    case Setup extends Callback("setup")
    case OnState extends Callback("onState")
    case OnMessage extends Callback("onMessage")
    case OnDestroy extends Callback("onDestroy")
  }
  enum Failure { case Denied, Aborted, Other }

  /**
   * The interpreter supplies each immutable check as an opaque delivery
   * identity. Decisions only propagate or compare that input; they never
   * allocate an identity. The lane retains it when startup is refused.
   */
  sealed trait Step[+S, +M]
  object Step {
    final class Check[+S, +M](val notification: Notification[S, M]) extends Step[S, M]
    object Check {
      def apply[S, M](notification: Notification[S, M]): Check[S, M] = new Check(notification)
    }
    final case class Call[+S, +M](check: Check[S, M], slot: Int, name: String) extends Step[S, M]
  }
  enum Phase[S, M] {
    case Recording(newest: Option[S], messages: Vector[M])
    case Delivering(targets: Int)
    case Stopped()
  }
  final case class State[S, M](
    guarded: Boolean,
    phase: Phase[S, M],
    admitted: Option[S],
    slots: Option[Vector[String]],
    unscheduledReported: Boolean
  )
  enum Event[+S, +M] {
    case Admit(state: S)
    case Written(state: S)
    case Published(message: M)
    case Register(name: String)
    case Begin
    case Failed(step: Step[S, M], failure: Failure)
    case Halt
    case Take
    case Unscheduled
  }
  enum Command[+S, +M] {
    case Deliver(notification: Notification[S, M], targets: Int)
    case Reject(last: Option[S])
    case Destroy(slot: Int, name: String, admitted: Option[S])
    case Report(text: String)
    case Close
  }
  enum CheckAction        { case Skip, Run, Pass         }
  enum NotificationAction { case Record, Deliver, Ignore }

  /** The lane owns progress; unrelated producer writes never change it. */
  type Progress[S, M] = Option[Step.Check[S, M]]

  def initial[S, M](guarded: Boolean): State[S, M] =
    State(guarded, Phase.Recording(None, Vector.empty), None, Some(Vector.empty), false)

  def stopped[S, M](state: State[S, M]): Boolean = state.phase match {
    case Phase.Stopped() => true
    case _               => false
  }

  def recorded[S, M](state: State[S, M]): Int = state.phase match {
    case Phase.Recording(newest, messages) => newest.size + messages.size
    case _                                 => 0
  }

  /** Admission needs no new result object for a delivering or stopped view. */
  def notificationAction[S, M](state: State[S, M]): NotificationAction = state.phase match {
    case Phase.Recording(_, _) => NotificationAction.Record
    case Phase.Delivering(_)   => NotificationAction.Deliver
    case Phase.Stopped()       => NotificationAction.Ignore
  }

  def targets[S, M](state: State[S, M]): Int = state.phase match {
    case Phase.Delivering(count) => math.max(0, math.min(count, state.slots.fold(0)(_.size)))
    case _                       => 0
  }

  def record[S, M](state: State[S, M], notification: Notification[S, M]): State[S, M] = state.phase match {
    case Phase.Recording(newest, messages) =>
      notification match {
        case Notification.State(value)   => state.copy(phase = Phase.Recording(Some(value), messages))
        case Notification.Message(value) => state.copy(phase = Phase.Recording(newest, messages :+ value))
      }
    case _ => state
  }

  def checkAction[S, M](state: State[S, M]): CheckAction = state.phase match {
    case Phase.Delivering(_) => if (state.guarded) CheckAction.Run else CheckAction.Pass
    case _                   => CheckAction.Skip
  }

  def passed[S, M](state: State[S, M], check: Step.Check[S, M]): Progress[S, M] = state.phase match {
    case Phase.Delivering(targets) if targets > 0 => Some(check)
    case _                                        => None
  }

  def mayCall[S, M](state: State[S, M], progress: Progress[S, M], call: Step.Call[S, M]): Boolean =
    state.phase match {
      case Phase.Delivering(targets) =>
        call.slot >= 0 && call.slot < targets && state.slots.exists(_.size > call.slot) &&
          progress.exists(_ eq call.check)
      case _ => false
    }

  /** The last call consumes admission even when its effect cannot start. */
  def endsDelivery[S, M](state: State[S, M], call: Step.Call[S, M]): Boolean = state.phase match {
    case Phase.Delivering(targets) => targets > 0 && call.slot == targets - 1
    case _                         => false
  }

  /**
   * Only an entered call updates this independently published per-extension
   * state.
   */
  def received[S, M](before: Option[S], notification: Notification[S, M]): Option[S] = notification match {
    case Notification.State(value) => Some(value)
    case _                         => before
  }

  def reportOf(name: String, callback: Callback, failure: Failure, stopped: Boolean): Option[String] =
    if (
      failure == Failure.Aborted ||
      (failure == Failure.Denied && stopped && (callback == Callback.OnState || callback == Callback.OnMessage))
    ) None
    else Some(s"Extension $name: ${callback.label} failed")

  private def enqueue[S, M](state: State[S, M], notification: Notification[S, M]): Vector[Command[S, M]] =
    notificationAction(state) match {
      case NotificationAction.Deliver => Vector(Command.Deliver(notification, targets(state)))
      case _                          => Vector.empty
    }

  private def notified[S, M](
    state: State[S, M],
    notification: Notification[S, M]
  ): (State[S, M], Vector[Command[S, M]]) =
    notificationAction(state) match {
      case NotificationAction.Record => (record(state, notification), Vector.empty)
      case _                         => (state, enqueue(state, notification))
    }

  def step[S, M](state: State[S, M], event: Event[S, M]): (State[S, M], Vector[Command[S, M]]) = {
    val unchanged = (state, Vector.empty[Command[S, M]])
    event match {
      case Event.Admit(value)     => (state.copy(admitted = Some(value)), Vector.empty)
      case Event.Written(value)   => notified(state, Notification.State(value))
      case Event.Published(value) => notified(state, Notification.Message(value))
      case Event.Register(name) =>
        state.slots match {
          case Some(slots) => (state.copy(slots = Some(slots :+ name)), Vector.empty)
          case None        => (state, Vector(Command.Reject(state.admitted)))
        }
      case Event.Begin =>
        state.phase match {
          case Phase.Recording(newest, messages) =>
            val notifications: Vector[Notification[S, M]] =
              newest.orElse(state.admitted).toVector.map(Notification.State(_)) ++ messages.map(Notification.Message(_))
            val next: State[S, M] = state.copy(phase = Phase.Delivering(state.slots.fold(0)(_.size)))
            (next, notifications.flatMap(enqueue(next, _)))
          case _ => unchanged
        }
      case Event.Failed(_: Step.Check[?, ?], failure) =>
        val reports = Option
          .when(failure != Failure.Denied)(Command.Report("Extension delivery was stopped: its check failed"))
          .toVector
        (state.copy(phase = Phase.Stopped()), Vector(Command.Close) ++ reports)
      case Event.Failed(Step.Call(check, _, name), failure) =>
        val callback = check.notification match {
          case Notification.State(_)   => Callback.OnState
          case Notification.Message(_) => Callback.OnMessage
        }
        (state, reportOf(name, callback, failure, stopped(state)).toVector.map(Command.Report(_)))
      case Event.Halt => (state.copy(phase = Phase.Stopped()), Vector(Command.Close))
      case Event.Take =>
        state.slots match {
          case None => unchanged
          case Some(slots) =>
            val close = Option.when(!stopped(state))(Command.Close).toVector
            val destroy = slots.zipWithIndex.reverse.map { case (name, index) =>
              Command.Destroy(index, name, state.admitted)
            }
            (state.copy(phase = Phase.Stopped(), slots = None), close ++ destroy)
        }
      case Event.Unscheduled =>
        if (state.unscheduledReported) unchanged
        else
          (state.copy(unscheduledReported = true), Vector(Command.Report("Extension delivery could not be scheduled")))
    }
  }
}
