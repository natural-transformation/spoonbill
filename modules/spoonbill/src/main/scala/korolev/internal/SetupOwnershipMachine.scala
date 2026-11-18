package spoonbill.internal

/**
 * Ownership of an upgrade before its transport handler takes over. Timers,
 * response cleanup and adapter-specific failure policies stay outside.
 */
private[spoonbill] object SetupOwnershipMachine {
  enum State { case Waiting, Prepared, Attached, Abandoned }
  enum Event { case Prepare, Attach, Abandon               }
  enum Command {
    case RetainPrepared, ReleaseIncoming, Transfer, RefuseAttachment, ReleaseInput, ReleasePrepared
  }

  def step(state: State, event: Event): (State, Vector[Command]) = (state, event) match {
    case (State.Waiting, Event.Prepare)  => (State.Prepared, Vector(Command.RetainPrepared))
    case (_, Event.Prepare)              => (state, Vector(Command.ReleaseIncoming))
    case (State.Prepared, Event.Attach)  => (State.Attached, Vector(Command.Transfer))
    case (_, Event.Attach)               => (state, Vector(Command.RefuseAttachment))
    case (State.Waiting, Event.Abandon)  => (State.Abandoned, Vector(Command.ReleaseInput))
    case (State.Prepared, Event.Abandon) => (State.Abandoned, Vector(Command.ReleasePrepared))
    case (_, Event.Abandon)              => (state, Vector.empty)
  }
}
