package zio.http.netty

/**
 * This timer is event-loop owned; no cross-executor callback ticket is needed.
 */
private[zio] object WebSocketFrameTimer {
  enum Phase { case Idle, Armed, Failed                       }
  enum State { case Idle, Failed; case Armed(startedAt: Long) }
  def begin(now: Long): State = State.Armed(now)
  inline def complete(phase: Phase, startedAt: Long, now: Long, timeoutNanos: Long): Phase =
    if (phase == Phase.Armed && now - startedAt < timeoutNanos) Phase.Idle else Phase.Failed
  def complete(state: State, now: Long, timeoutNanos: Long): State = state match {
    case State.Armed(startedAt) if complete(Phase.Armed, startedAt, now, timeoutNanos) == Phase.Idle => State.Idle
    case _                                                                                           => State.Failed
  }
}
