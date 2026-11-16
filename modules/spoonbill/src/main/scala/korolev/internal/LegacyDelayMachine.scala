package spoonbill.internal

/**
 * Legacy delay completion and cancellation-handle installation are independent.
 * Cancel invokes an installed handle; it does not make the delay finished. A
 * trusted scheduler may fire before returning its handle.
 */
private[spoonbill] object LegacyDelayMachine {
  enum State   { case Uninstalled, Armed, FinishedUninstalled, FinishedArmed         }
  enum Event   { case Installed, Fired, ScheduleFailed, Cancel                       }
  enum Command { case RetainHandle, RunCallback, ReportFailure, CancelHandle, Ignore }

  def finished(state: State): Boolean = state match {
    case State.FinishedUninstalled | State.FinishedArmed => true
    case _                                               => false
  }

  private def finish(state: State): State = state match {
    case State.Uninstalled | State.FinishedUninstalled => State.FinishedUninstalled
    case _                                             => State.FinishedArmed
  }

  def step(state: State, event: Event): (State, Command) = event match {
    case Event.Installed =>
      (if (finished(state)) State.FinishedArmed else State.Armed, Command.RetainHandle)
    case Event.Fired          => (finish(state), Command.RunCallback)
    case Event.ScheduleFailed => (finish(state), Command.ReportFailure)
    case Event.Cancel =>
      val command = state match {
        case State.Armed | State.FinishedArmed => Command.CancelHandle
        case _                                 => Command.Ignore
      }
      (state, command)
  }
}
