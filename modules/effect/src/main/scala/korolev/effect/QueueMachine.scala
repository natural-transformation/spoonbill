package spoonbill.effect

/**
 * Compact per-operation decisions; buffer values and callback handles stay in
 * the interpreter.
 */
private[effect] object QueueMachine {
  enum Admission { case Open, Stopped, Closed }
  final case class State(
    admission: Admission = Admission.Open,
    error: Option[Throwable] = None,
    cancellation: CompletionLatchMachine.State = CompletionLatchMachine.State.Pending
  )
  enum Pull {
    case Buffered, End, Wait
    case Failed(error: Throwable)
  }
  val initial: State = State()

  def isClosed(state: State): Boolean = state.admission == Admission.Closed
  def mayOffer(state: State): Boolean = state.admission == Admission.Open

  def pull(state: State, buffered: Boolean): Pull = state.error match {
    case Some(error)              => Pull.Failed(error)
    case None if isClosed(state)  => Pull.End
    case None if buffered         => Pull.Buffered
    case None if !mayOffer(state) => Pull.End
    case None                     => Pull.Wait
  }

  def stop(state: State): State =
    if (isClosed(state)) state else state.copy(admission = Admission.Stopped)
  def close(state: State): State                  = state.copy(admission = Admission.Closed)
  def fail(state: State, error: Throwable): State = state.copy(error = Some(error))

  def cancel(state: State): (State, Boolean) = {
    val (next, command) = CompletionLatchMachine.step(state.cancellation, CompletionLatchMachine.Event.Complete)
    (state.copy(admission = Admission.Closed, cancellation = next), command == CompletionLatchMachine.Command.Publish)
  }

  def cancellationReplays(state: State): Boolean =
    CompletionLatchMachine.step(state.cancellation, CompletionLatchMachine.Event.Observe)._2 ==
      CompletionLatchMachine.Command.Replay
}
