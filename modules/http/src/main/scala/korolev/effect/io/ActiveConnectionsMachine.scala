package spoonbill.effect.io

/** Acceptance and handler ownership are separate: a handler failure does not
  * stop admission, and stopping acceptance does not retire live handlers.
  * The interpreter owns the once-completed shutdown promise.
  */
private[io] object ActiveConnectionsMachine {
  final case class State(active: Int, accepting: Boolean, failure: Option[Throwable])
  val initial: State = State(0, true, None)

  def started(state: State): State = state.copy(active = state.active + 1)

  def finished(state: State, error: Option[Throwable]): State =
    state.copy(active = state.active - 1, failure = state.failure.orElse(error))

  def stopped(state: State, error: Option[Throwable]): State =
    state.copy(accepting = false, failure = state.failure.orElse(error))

  def completion(state: State): Option[Either[Throwable, Unit]] =
    Option.when(!state.accepting && state.active == 0)(state.failure.toLeft(()))
}
