package spoonbill.internal

/**
 * Extension lifecycle decisions. Handler values and effect scheduling belong to
 * the interpreter.
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
   * The lane retains this identity even if the effect cannot start or teardown
   * takes the slots.
   */
  enum Step[+S, +M] {
    def delivery: Long
    case Check(delivery: Long, notification: Notification[S, M])
    case Call(delivery: Long, slot: Int, name: String, notification: Notification[S, M])
  }
  final case class Slot[S](name: String, received: Option[S])
  enum Phase[S, M] {
    case Recording(newest: Option[S], messages: Vector[M])
    case Delivering(targets: Int, deliveries: Long, passed: Option[Long], waiting: Vector[Step[S, M]])
    case Stopped()
  }
  final case class State[S, M](
    guarded: Boolean,
    phase: Phase[S, M],
    admitted: Option[S],
    slots: Option[Vector[Slot[S]]],
    unscheduledReported: Boolean
  )
  enum Event[+S, +M] {
    case Admit(state: S)
    case Written(state: S)
    case Published(message: M)
    case Register(name: String)
    case Begin
    case Next
    case Start(step: Step[S, M])
    case Passed(delivery: Long)
    case Failed(step: Step[S, M], failure: Failure)
    case Halt
    case Take
    case Unscheduled
  }
  enum Command[+S, +M] {
    case Wake
    case Hand(step: Step[S, M])
    case RunCheck(notification: Notification[S, M])
    case RunCall(slot: Int, notification: Notification[S, M])
    case Reject(last: Option[S])
    case Destroy(slot: Int, name: String, last: Option[S])
    case Report(text: String)
    case Close
  }

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

  def waiting[S, M](state: State[S, M]): Int = state.phase match {
    case Phase.Delivering(_, _, _, steps) => steps.size
    case _                                => 0
  }

  def reportOf(name: String, callback: Callback, failure: Failure, stopped: Boolean): Option[String] =
    if (
      failure == Failure.Aborted ||
      (failure == Failure.Denied && stopped && (callback == Callback.OnState || callback == Callback.OnMessage))
    ) None
    else Some(s"Extension $name: ${callback.label} failed")

  private def enqueue[S, M](
    state: State[S, M],
    notification: Notification[S, M]
  ): (State[S, M], Vector[Command[S, M]]) =
    state.phase match {
      case Phase.Delivering(targets, deliveries, passed, waiting) =>
        val delivery = deliveries + 1L // Only adjacent deliveries must differ; signed wrap is safe.
        val slots    = state.slots.getOrElse(Vector.empty)
        val count    = math.max(0, math.min(targets, slots.size))
        val steps = Vector.tabulate[Step[S, M]](count + 1) { position =>
          if (position == 0) Step.Check(delivery, notification)
          else {
            val index = position - 1
            Step.Call(delivery, index, slots(index).name, notification)
          }
        }
        (state.copy(phase = Phase.Delivering(targets, delivery, passed, waiting ++ steps)), Vector(Command.Wake))
      case _ => (state, Vector.empty)
    }

  def step[S, M](state: State[S, M], event: Event[S, M]): (State[S, M], Vector[Command[S, M]]) = {
    val unchanged = (state, Vector.empty[Command[S, M]])
    event match {
      case Event.Admit(value) => (state.copy(admitted = Some(value)), Vector.empty)
      case Event.Written(value) =>
        state.phase match {
          case Phase.Recording(_, messages) =>
            (state.copy(phase = Phase.Recording(Some(value), messages)), Vector.empty)
          case _ => enqueue(state, Notification.State(value))
        }
      case Event.Published(value) =>
        state.phase match {
          case Phase.Recording(newest, messages) =>
            (state.copy(phase = Phase.Recording(newest, messages :+ value)), Vector.empty)
          case _ => enqueue(state, Notification.Message(value))
        }
      case Event.Register(name) =>
        state.slots match {
          case Some(slots) => (state.copy(slots = Some(slots :+ Slot(name, None))), Vector.empty)
          case None        => (state, Vector(Command.Reject(state.admitted)))
        }
      case Event.Begin =>
        state.phase match {
          case Phase.Recording(newest, messages) =>
            val notifications: Vector[Notification[S, M]] =
              newest.orElse(state.admitted).toVector.map(Notification.State(_)) ++ messages.map(Notification.Message(_))
            val delivering: State[S, M] =
              state.copy(phase = Phase.Delivering(state.slots.fold(0)(_.size), 0L, None, Vector.empty))
            val next = notifications.foldLeft(delivering)((before, notification) => enqueue(before, notification)._1)
            (next, Option.when(waiting(next) > 0)(Command.Wake).toVector)
          case _ => unchanged
        }
      case Event.Next =>
        state.phase match {
          case Phase.Delivering(targets, deliveries, passed, waiting) if waiting.nonEmpty =>
            (
              state.copy(phase = Phase.Delivering(targets, deliveries, passed, waiting.tail)),
              Vector(Command.Hand(waiting.head))
            )
          case _ => unchanged
        }
      case Event.Start(Step.Check(delivery, notification)) =>
        state.phase match {
          case Phase.Delivering(targets, deliveries, _, waiting) =>
            if (state.guarded) (state, Vector(Command.RunCheck(notification)))
            else (state.copy(phase = Phase.Delivering(targets, deliveries, Some(delivery), waiting)), Vector.empty)
          case _ => unchanged
        }
      case Event.Passed(delivery) =>
        state.phase match {
          case Phase.Delivering(targets, deliveries, _, waiting) =>
            (state.copy(phase = Phase.Delivering(targets, deliveries, Some(delivery), waiting)), Vector.empty)
          case _ => unchanged
        }
      case Event.Start(Step.Call(delivery, index, _, notification)) =>
        state.phase match {
          case Phase.Delivering(targets, _, Some(passed), _) if passed == delivery && index >= 0 && index < targets =>
            state.slots.flatMap(_.lift(index)).fold(unchanged) { slot =>
              val next = notification match {
                case Notification.State(value) =>
                  state.copy(slots = state.slots.map(_.updated(index, slot.copy(received = Some(value)))))
                case _ => state
              }
              (next, Vector(Command.RunCall(index, notification)))
            }
          case _ => unchanged
        }
      case Event.Failed(_: Step.Check[?, ?], failure) =>
        val reports = Option
          .when(failure != Failure.Denied)(Command.Report("Extension delivery was stopped: its check failed"))
          .toVector
        (state.copy(phase = Phase.Stopped()), Vector(Command.Close) ++ reports)
      case Event.Failed(Step.Call(_, _, name, notification), failure) =>
        val callback = notification match {
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
            val destroy = slots.zipWithIndex.reverse.map { case (slot, index) =>
              Command.Destroy(index, slot.name, slot.received.orElse(state.admitted))
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
