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

  /** Stop releases producer obligations even while accepted values remain. */
  enum StopCommand { case ReleaseProducers, ReleaseProducersAndEndPulls }
  val initial: State = State()

  def isClosed(state: State): Boolean = state.admission == Admission.Closed
  def mayOffer(state: State): Boolean = state.admission == Admission.Open

  /**
   * A producer whose value did not fit may wait for room only while a pull can
   * still make room. A failed queue answers every pull with its error and takes
   * no value, so that wait would have no end. Failure releases waiting
   * producers as stop and close do; admission itself stays as it is.
   */
  def mayAwaitCapacity(state: State): Boolean = mayOffer(state) && state.error.isEmpty

  /**
   * A producer that had to wait puts its value in the buffer only by the rule
   * that let it wait. A drain can make room between a failure and the
   * producer's next attempt, and a released producer must not use that room.
   * The two rules have to agree: a producer that may wait but not commit would
   * be woken by free room again and again.
   */
  def mayCommitAfterWait(state: State): Boolean = mayAwaitCapacity(state)

  def pull(state: State, buffered: Boolean): Pull = state.error match {
    case Some(error)              => Pull.Failed(error)
    case None if isClosed(state)  => Pull.End
    case None if buffered         => Pull.Buffered
    case None if !mayOffer(state) => Pull.End
    case None                     => Pull.Wait
  }

  def stop(state: State, buffered: Boolean): (State, StopCommand) = {
    val next    = if (isClosed(state)) state else state.copy(admission = Admission.Stopped)
    val command = if (buffered) StopCommand.ReleaseProducers else StopCommand.ReleaseProducersAndEndPulls
    (next, command)
  }
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
