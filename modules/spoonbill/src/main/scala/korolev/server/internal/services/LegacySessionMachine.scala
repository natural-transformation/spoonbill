package spoonbill.server.internal.services

/**
 * Legacy publication is owned by the application registry, while this machine
 * fences extension setup after connection loss and selects one retirement.
 * Rollback and disconnected retirement retain distinct restore/registry
 * effects.
 *
 * A rollback has one step after its stages: its failure is taken out of the
 * registry, once the registry has given it to the attaches that waited.
 */
private[spoonbill] object LegacySessionMachine {
  enum Connection { case Open, Closed            }
  enum Cause      { case Disconnected, Abandoned }
  enum Stage {
    case StopExtensions, CancelInput, CloseFrontend, DestroyApplication
    case DestroyExtensions, Restore, RemoveStorage, SettleRegistry
  }
  enum Retirement {
    case Idle
    case Running(cause: Cause, stage: Stage)
    case Done(cause: Cause)

    /** A rollback whose failure is out of the registry. */
    case Forgotten
    case Failed(cause: Cause)
  }
  final case class State(connection: Connection = Connection.Open, retirement: Retirement = Retirement.Idle)
  enum Event {
    case ConnectionClosed, Failed
    case Retire(cause: Cause)
    case Finished(stage: Stage)

    /**
     * The attach failed before it had a view. There is nothing to retire.
     *
     * @param removesState
     *   whether the storage is local to this process: then the state of the
     *   page goes, because no view owns it
     */
    case NothingBuilt(removesState: Boolean)

    /** The registry has given the failure of an attach to every attach that waited for it. */
    case Published
  }
  enum Command {
    case None
    case Run(stage: Stage)

    /** Take the failure of this attach out of the registry. */
    case Forget

    /** Remove the stored state of the session. */
    case RemoveState
  }
  def connectionClosed(state: State): Boolean = state.connection == Connection.Closed

  private def after(stage: Stage): Option[Stage] = stage match {
    case Stage.StopExtensions     => Some(Stage.CancelInput)
    case Stage.CancelInput        => Some(Stage.CloseFrontend)
    case Stage.CloseFrontend      => Some(Stage.DestroyApplication)
    case Stage.DestroyApplication => Some(Stage.DestroyExtensions)
    case Stage.DestroyExtensions  => Some(Stage.Restore)
    case Stage.Restore            => Some(Stage.RemoveStorage)
    case Stage.RemoveStorage      => Some(Stage.SettleRegistry)
    case Stage.SettleRegistry     => None
  }
  def step(state: State, event: Event): (State, Command) = event match {
    case Event.ConnectionClosed => (state.copy(connection = Connection.Closed), Command.None)
    case Event.Retire(cause) if state.retirement == Retirement.Idle =>
      (state.copy(retirement = Retirement.Running(cause, Stage.StopExtensions)), Command.Run(Stage.StopExtensions))
    case Event.Finished(stage) =>
      state.retirement match {
        case Retirement.Running(cause, current) if current == stage =>
          after(stage) match {
            case Some(next) => (state.copy(retirement = Retirement.Running(cause, next)), Command.Run(next))
            case None       => (state.copy(retirement = Retirement.Done(cause)), Command.None)
          }
        case _ => (state, Command.None)
      }
    case Event.Failed =>
      state.retirement match {
        case Retirement.Running(cause, _) => (state.copy(retirement = Retirement.Failed(cause)), Command.None)
        case _                            => (state, Command.None)
      }
    case Event.NothingBuilt(removesState) if state.retirement == Retirement.Idle =>
      (state.copy(retirement = Retirement.Done(Cause.Abandoned)), if (removesState) Command.RemoveState else Command.None)
    case Event.Published =>
      state.retirement match {
        // Only the attach that rolled back takes the failure out, and once.
        // An attach that only waited leaves the registry alone.
        case Retirement.Done(Cause.Abandoned) => (state.copy(retirement = Retirement.Forgotten), Command.Forget)
        case _                                => (state, Command.None)
      }
    case _ => (state, Command.None)
  }
}
