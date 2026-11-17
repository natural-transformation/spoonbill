package spoonbill.effect

/**
 * A resource handle can arrive before or after its owner's once-only finish.
 * The interpreter retains/detaches handles atomically with this state, then
 * performs cancellation and notification outside that section.
 */
private[spoonbill] object LateHandleMachine {
  enum State   { case Waiting, Installed, Finished                                 }
  enum Event   { case Install, Finish                                              }
  enum Command { case StoreHandle, CancelIncoming, Finish, CancelAndFinish, Ignore }

  def step(state: State, event: Event): (State, Command) = (state, event) match {
    case (State.Finished, Event.Install) => (State.Finished, Command.CancelIncoming)
    case (_, Event.Install)              => (State.Installed, Command.StoreHandle)
    case (State.Waiting, Event.Finish)   => (State.Finished, Command.Finish)
    case (State.Installed, Event.Finish) => (State.Finished, Command.CancelAndFinish)
    case (State.Finished, Event.Finish)  => (State.Finished, Command.Ignore)
  }
}
