package spoonbill.auth

import java.time.Instant

/**
 * Publication state. Record rollback never rewinds already observed frontiers.
 */
private[auth] object MemoryBrowserStoreMachine {
  final case class State[R](
    records: Option[R],
    issuanceSequence: Long = 0L,
    bindingGenerationFloor: Long = 0L,
    viewEpochFloor: Long = 1L,
    observed: Instant = Instant.MIN
  )

  def initial[R](records: R): State[R] = State(Some(records))

  def observe[R](state: State[R], time: Instant): State[R] =
    if (time.isAfter(state.observed)) state.copy(observed = time) else state

  def publish[R](state: State[R], records: R): State[R] = state.copy(records = Some(records))

  def issue[R](state: State[R], maximum: Long): Option[State[R]] =
    Option.when(state.issuanceSequence < maximum)(state.copy(issuanceSequence = state.issuanceSequence + 1L))

  def reclaim[R](state: State[R], bindingFloor: Long, viewFloor: Long): State[R] =
    state.copy(bindingGenerationFloor = bindingFloor, viewEpochFloor = viewFloor)

  def close[R](state: State[R]): State[R] = state.copy(records = None)
}
