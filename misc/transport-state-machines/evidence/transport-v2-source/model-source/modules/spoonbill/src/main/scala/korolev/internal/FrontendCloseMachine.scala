package spoonbill.internal

/**
 * Execution ownership differs from a result latch: recursive close does not
 * await or replay cleanup. The output fence is published before this claim.
 */
private[internal] object FrontendCloseMachine {
  enum State { case Unclaimed, Claimed }
  enum Command {
    case FailActions, CancelDownloads, CloseStrings, CloseForms, CloseFiles, CloseFileNames
    case CloseSensitiveOutcomes, CloseSensitiveRuntime, CancelOutput, CancelActions, CloseRacks, CancelInput
  }
  val commands: List[Command] = Command.values.toList
  def close(state: State): (State, List[Command]) = state match {
    case State.Unclaimed => (State.Claimed, commands)
    case State.Claimed   => (state, Nil)
  }
}
