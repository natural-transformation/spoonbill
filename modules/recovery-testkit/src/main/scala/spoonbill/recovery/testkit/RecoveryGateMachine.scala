package spoonbill.recovery.testkit

import spoonbill.recovery.FaultDecision

/** The gate's decision and arrival are independent of its callback handles. */
private[testkit] object RecoveryGateMachine {
  enum State {
    case Waiting, Arrived
    case ResumedBeforeArrival(value: FaultDecision)
    case ResumedAfterArrival(value: FaultDecision)
  }
  final case class Arrival(state: State, decision: Option[FaultDecision], first: Boolean)

  def reached(state: State): Boolean = state match {
    case State.Arrived | State.ResumedAfterArrival(_) => true
    case _                                            => false
  }

  def resume(state: State, value: FaultDecision): State =
    if (reached(state)) State.ResumedAfterArrival(value) else State.ResumedBeforeArrival(value)

  def arrive(state: State): Arrival = state match {
    case State.Waiting => Arrival(State.Arrived, None, first = true)
    case State.ResumedBeforeArrival(value) =>
      Arrival(State.ResumedAfterArrival(value), Some(value), first = true)
    case _ => Arrival(state, Some(FaultDecision.Proceed), first = false)
  }
}
