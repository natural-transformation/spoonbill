package spoonbill.server.internal.services

/**
 * Guarded attachment admission and ordered retirement. Physical resources,
 * errors and effects belong to the interpreter. Cleanup ownership is once-only;
 * a second request does not join the first. An unfinished drain cannot advance
 * to resource release or free the local attachment.
 */
private[spoonbill] object GuardedSessionMachine {
  enum Attachment { case Unclaimed, Acquiring, Attached }
  enum Admission  { case Open, Denied                   }
  enum Terminal   { case Pending, Signalled             }
  enum Storage    { case Unacquired, Acquired           }
  enum Stage {
    case StopExtensions, StopComponents, DenyAndStopIdle, RejectReady, CloseFrontend
    case DrainWrites, DestroyApplication, CloseGuard, DestroyExtensions
    case RemoveStorage, RemoveApplication, ReleaseAttachment
  }
  enum Cleanup {
    case Idle
    case Running(stage: Stage)
    case Done, Failed
  }
  final case class State(
    attachment: Attachment = Attachment.Unclaimed,
    admission: Admission = Admission.Open,
    terminal: Terminal = Terminal.Pending,
    storage: Storage = Storage.Unacquired,
    cleanup: Cleanup = Cleanup.Idle
  )
  enum Event {
    case Claim, Attach, Deny, Signal, AcquireStorage, BeginCleanup, CleanupFailed
    case Finished(stage: Stage)
  }
  enum Command {
    case None, Signal
    case Run(stage: Stage)
  }

  def live(state: State): Boolean          = state.admission == Admission.Open
  def signalled(state: State): Boolean     = state.terminal == Terminal.Signalled
  def ownsStorage(state: State): Boolean   = state.storage == Storage.Acquired
  def needsRollback(state: State): Boolean = state.attachment == Attachment.Acquiring

  private def after(stage: Stage): Option[Stage] = stage match {
    case Stage.StopExtensions     => Some(Stage.StopComponents)
    case Stage.StopComponents     => Some(Stage.DenyAndStopIdle)
    case Stage.DenyAndStopIdle    => Some(Stage.RejectReady)
    case Stage.RejectReady        => Some(Stage.CloseFrontend)
    case Stage.CloseFrontend      => Some(Stage.DrainWrites)
    case Stage.DrainWrites        => Some(Stage.DestroyApplication)
    case Stage.DestroyApplication => Some(Stage.CloseGuard)
    case Stage.CloseGuard         => Some(Stage.DestroyExtensions)
    case Stage.DestroyExtensions  => Some(Stage.RemoveStorage)
    case Stage.RemoveStorage      => Some(Stage.RemoveApplication)
    case Stage.RemoveApplication  => Some(Stage.ReleaseAttachment)
    case Stage.ReleaseAttachment  => None
  }

  def step(state: State, event: Event): (State, Command) = event match {
    case Event.Claim if state.attachment == Attachment.Unclaimed =>
      (state.copy(attachment = Attachment.Acquiring), Command.None)
    case Event.Attach => (state.copy(attachment = Attachment.Attached), Command.None)
    case Event.Deny   => (state.copy(admission = Admission.Denied), Command.None)
    case Event.Signal if state.terminal == Terminal.Pending =>
      (state.copy(terminal = Terminal.Signalled), Command.Signal)
    case Event.AcquireStorage => (state.copy(storage = Storage.Acquired), Command.None)
    case Event.BeginCleanup if state.cleanup == Cleanup.Idle =>
      (state.copy(cleanup = Cleanup.Running(Stage.StopExtensions)), Command.Run(Stage.StopExtensions))
    case Event.Finished(stage) if state.cleanup == Cleanup.Running(stage) =>
      after(stage) match {
        case Some(next) => (state.copy(cleanup = Cleanup.Running(next)), Command.Run(next))
        case None       => (state.copy(cleanup = Cleanup.Done), Command.None)
      }
    case Event.CleanupFailed => (state.copy(cleanup = Cleanup.Failed), Command.None)
    case _                   => (state, Command.None)
  }
}
