package spoonbill.server.internal.services

/**
 * Subscription and publication activity of one long-polling topic. Registry
 * membership, clocks and installed timer handles belong to the service.
 */
private[services] object OrphanTopicMachine {
  enum State {
    def lastActivityMillis: Long
    case Orphan(lastActivityMillis: Long)
    case Subscribed(lastActivityMillis: Long)
  }
  enum Event {
    case Activity(nowMillis: Long)
    case Subscribe
  }
  enum Command { case CancelCleanup, Ignore }

  def initial(nowMillis: Long): State = State.Orphan(nowMillis)
  def subscribed(state: State): Boolean = state match {
    case State.Subscribed(_) => true
    case _                   => false
  }
  def shouldSchedule(state: State, timeoutMillis: Long): Boolean = !subscribed(state) && timeoutMillis > 0L
  def due(lastActivityMillis: Long, nowMillis: Long, timeoutMillis: Long): Boolean =
    nowMillis - lastActivityMillis >= timeoutMillis

  def step(state: State, event: Event): (State, Command) = event match {
    case Event.Activity(now) =>
      val next: State = state match {
        case State.Orphan(_)     => State.Orphan(now)
        case State.Subscribed(_) => State.Subscribed(now)
      }
      (next, Command.Ignore)
    case Event.Subscribe =>
      state match {
        case State.Orphan(last) => (State.Subscribed(last), Command.CancelCleanup)
        case _                  => (state, Command.Ignore)
      }
  }
}
