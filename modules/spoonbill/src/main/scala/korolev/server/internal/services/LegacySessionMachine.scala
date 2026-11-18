package spoonbill.server.internal.services

/**
 * Legacy publication is owned by the application registry, while this machine
 * fences extension setup after connection loss and selects one retirement.
 * Rollback and disconnected retirement retain distinct restore/registry
 * effects.
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
    case Failed(cause: Cause)
  }
  final case class State(connection: Connection = Connection.Open, retirement: Retirement = Retirement.Idle)
  enum Event {
    case ConnectionClosed, Failed
    case Retire(cause: Cause)
    case Finished(stage: Stage)
  }
  enum Command {
    case None
    case Run(stage: Stage)
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
    case _ => (state, Command.None)
  }
}
