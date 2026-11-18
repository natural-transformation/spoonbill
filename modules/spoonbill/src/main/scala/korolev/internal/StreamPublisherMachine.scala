package spoonbill.internal

/** Publisher termination and positive-demand decisions, independent of effects. */
private[spoonbill] object StreamPublisherMachine {
  enum State { case Active, DemandRejected, Canceled, Completed, Failed }
  enum Signal { case Idle, Entering }
  enum Event {
    case Cancel, Complete, Fail, RejectDemand
    case ReportDemand(signal: Signal)
  }
  enum Command { case None, CancelSource, Complete, Fail, RejectDemand, ReportDemand }

  val initial: State = State.Active
  def live(state: State): Boolean = state == State.Active

  def step(state: State, event: Event): (State, Command) =
    if (state == State.DemandRejected && event == Event.ReportDemand(Signal.Idle))
      (State.Failed, Command.ReportDemand)
    else if (!live(state)) (state, Command.None)
    else event match {
      case Event.Cancel => (State.Canceled, Command.CancelSource)
      case Event.Complete => (State.Completed, Command.Complete)
      case Event.Fail => (State.Failed, Command.Fail)
      case Event.RejectDemand => (State.DemandRejected, Command.RejectDemand)
      case _: Event.ReportDemand => (state, Command.None)
    }

  def canReportDemand(state: State, signal: Signal): Boolean =
    state == State.DemandRejected && signal == Signal.Idle

  def unbounded(demand: Long): Boolean = demand == Long.MaxValue

  /** Nonpositive inputs retain the existing countdown policy. */
  def addDemand(current: Long, incoming: Long): Long =
    if (incoming <= 0 || unbounded(current)) current
    else if (current > Long.MaxValue - incoming) Long.MaxValue
    else current + incoming

  /** Called after the interpreter has observed positive demand. */
  def consumed(demand: Long): Long = if (unbounded(demand)) demand else demand - 1
}
