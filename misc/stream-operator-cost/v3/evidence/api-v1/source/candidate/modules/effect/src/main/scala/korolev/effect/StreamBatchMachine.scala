package spoonbill.effect

import scala.collection.immutable.Queue

/**
 * One delayed batch at a time, with FIFO waiting consumers and no timer
 * handles.
 */
private[effect] object StreamBatchMachine {
  final class Ticket
  enum Phase { case Open, Closed }
  final case class Active[A](ticket: Ticket, first: Option[A] = None)
  final case class State[A](
    phase: Phase = Phase.Open,
    active: Option[Active[A]] = None,
    waiting: Queue[Ticket] = Queue.empty
  )

  def begin[A](state: State[A], ticket: Ticket): (State[A], Boolean) =
    if (state.phase == Phase.Closed) (state, false)
    else if (state.active.isEmpty) (state.copy(active = Some(Active[A](ticket))), true)
    else (state.copy(waiting = state.waiting.enqueue(ticket)), false)

  def owns[A](state: State[A], ticket: Ticket): Boolean =
    state.phase == Phase.Open && state.active.exists(_.ticket eq ticket)

  def first[A](state: State[A], ticket: Ticket, value: A): State[A] =
    if (owns(state, ticket)) state.copy(active = Some(Active(ticket, Some(value)))) else state

  def finish[A](state: State[A], ticket: Ticket): (State[A], Option[Ticket]) =
    if (!owns(state, ticket)) (state, None)
    else
      state.waiting.dequeueOption match {
        case Some((next, rest)) => (state.copy(active = Some(Active[A](next)), waiting = rest), Some(next))
        case None               => (state.copy(active = None), None)
      }

  def close[A](state: State[A]): State[A] = state.copy(phase = Phase.Closed, active = None, waiting = Queue.empty)
}
