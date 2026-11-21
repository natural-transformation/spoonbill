package spoonbill.server.internal.services

/**
 * Legacy publication is owned by the application registry, while this machine
 * fences extension setup after connection loss and selects one retirement.
 * Rollback and disconnected retirement retain distinct restore/registry
 * effects.
 *
 * The retirement also decides what becomes of the stored state. And a
 * rollback has one step after its stages: its failure is taken out of the
 * registry, once the registry has given it to the attaches that waited.
 */
private[spoonbill] object LegacySessionMachine {
  enum Connection { case Open, Closed            }
  enum Cause      { case Disconnected, Abandoned }
  enum Stage {
    case StopExtensions, CancelInput, CloseFrontend, DestroyApplication
    case DestroyExtensions, Restore, RemoveStorage, SettleRegistry
  }

  /** What the retirement did with the stored state of the session. */
  enum Storage {

    /** Nothing is decided: the view holds the state. */
    case Held

    /** Left in place for the next view, because the page goes on. */
    case Left

    /** The storage was asked to remove it. */
    case Removed
  }
  enum Retirement {
    case Idle
    case Running(cause: Cause, stage: Stage)
    case Done(cause: Cause)

    /** A rollback whose failure is out of the registry. */
    case Forgotten
    case Failed(cause: Cause)
  }
  final case class State(
    connection: Connection = Connection.Open,
    retirement: Retirement = Retirement.Idle,
    storage: Storage = Storage.Held
  )
  enum Event {
    case ConnectionClosed, Failed
    case Retire(cause: Cause)
    case Finished(stage: Stage)

    /**
     * At the storage stage, what the interpreter sees there: a newer transport
     * has replaced the one of this view, or an attach of the session waits
     * for the view to leave.
     */
    case Release(replaced: Boolean, awaited: Boolean)

    /** The attach failed before it had a view. There is nothing to retire. */
    case NothingBuilt

    /** The registry has given the failure of an attach to every attach that waited for it. */
    case Published
  }
  enum Command {
    case None
    case Run(stage: Stage)

    /** Ask the storage to remove the state. */
    case RemoveState

    /** Take the failure of this attach out of the registry, and say that it is out. */
    case Forget
  }
  def connectionClosed(state: State): Boolean = state.connection == Connection.Closed

  /** The retirement left the stored state in place for the next view. */
  def stateLeft(state: State): Boolean = state.storage == Storage.Left

  /** This attach rolled back what it had built. A failure that it meets is its own, not one that it waited for. */
  def rolledBack(state: State): Boolean = state.retirement match {
    case Retirement.Done(Cause.Abandoned) | Retirement.Forgotten => true
    case _                                                        => false
  }
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
    case Event.Release(replaced, awaited) =>
      state.retirement match {
        // Decided once, at its stage. When the page goes on, the next view
        // takes the state over. A storage may only start a removal, and that
        // removal could still run when the next view has attached.
        case Retirement.Running(_, Stage.RemoveStorage) if state.storage == Storage.Held =>
          if (replaced || awaited) (state.copy(storage = Storage.Left), Command.None)
          else (state.copy(storage = Storage.Removed), Command.RemoveState)
        case _ => (state, Command.None)
      }
    case Event.NothingBuilt if state.retirement == Retirement.Idle =>
      (state.copy(retirement = Retirement.Done(Cause.Abandoned)), Command.None)
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
