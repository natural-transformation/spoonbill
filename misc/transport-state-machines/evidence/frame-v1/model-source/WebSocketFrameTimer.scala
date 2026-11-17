package zio.http.netty

/**
 * This timer is event-loop owned; no cross-executor callback ticket is needed.
 */
private[zio] object WebSocketFrameTimer {
  enum State { case Idle, Failed; case Armed(startedAt: Long) }
  def begin(now: Long): State = State.Armed(now)
  def complete(state: State, now: Long, timeoutNanos: Long): State = state match {
    case State.Armed(startedAt) if now - startedAt < timeoutNanos => State.Idle
    case _                                                        => State.Failed
  }
}
