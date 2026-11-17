package spoonbill.auth

/**
 * View ownership metadata only. Credentials, provider capabilities, leases,
 * clock observations and policy callbacks remain interpreter resources.
 */
private[auth] object BrowserViewMachine {
  enum Phase          { case Claimed, Prepared, Live, RetiringAttached, RetiringDetached, Released }
  enum Initialization { case Unknown, Required, Ready                                              }
  enum Retirement     { case AuthorityDenied, ReplacementCompleted, Release                        }
  enum GuardRelease   { case Available, Claimed                                                    }

  final case class State(
    phase: Phase,
    initialization: Initialization = Initialization.Unknown,
    retirement: Option[Retirement] = None,
    guardRelease: GuardRelease = GuardRelease.Available
  )
  val initial: State = State(Phase.Claimed)

  def owns(state: State): Boolean     = state.phase != Phase.Released
  def live(state: State): Boolean     = state.phase == Phase.Live
  def attached(state: State): Boolean = state.phase == Phase.Live || state.phase == Phase.RetiringAttached
  def retiring(state: State): Boolean = state.phase match {
    case Phase.RetiringAttached | Phase.RetiringDetached | Phase.Released => true
    case _                                                                => false
  }

  def prepare(state: State, reconstructed: Boolean): State =
    if (state.phase != Phase.Claimed) state
    else
      state.copy(
        phase = Phase.Prepared,
        initialization = if (reconstructed) Initialization.Required else Initialization.Ready
      )

  def publish(state: State): State =
    if (state.phase == Phase.Prepared) state.copy(phase = Phase.Live) else state

  /**
   * Marking in a read turn does not detach local presentation. Only the
   * observation which wins this claim owes a later write-turn detach.
   */
  def retire(state: State, reason: Retirement): (State, Boolean) =
    if (retiring(state)) (state, false)
    else
      (
        state.copy(
          phase = if (attached(state)) Phase.RetiringAttached else Phase.RetiringDetached,
          retirement = Some(reason)
        ),
        true
      )

  def detach(state: State, reason: Retirement): State =
    if (!owns(state)) state
    else state.copy(phase = Phase.RetiringDetached, retirement = state.retirement.orElse(Some(reason)))

  /**
   * Ownership stays retained across failed/uncertain release until this edge.
   */
  def releaseAcknowledged(state: State): State =
    if (state.phase == Phase.RetiringDetached) state.copy(phase = Phase.Released) else state

  def initialized(state: State): State =
    if (state.initialization == Initialization.Required) state.copy(initialization = Initialization.Ready) else state

  /**
   * Capture before detach marks retirement; a retry must never retain again.
   */
  def retainOnGuardClose(state: State): Boolean =
    !retiring(state) && state.initialization == Initialization.Ready

  def claimGuardClose(state: State): (State, Boolean) =
    if (state.guardRelease == GuardRelease.Claimed) (state, false)
    else (state.copy(guardRelease = GuardRelease.Claimed), true)

  def retryGuardClose(state: State): State =
    if (state.guardRelease == GuardRelease.Available) state
    else state.copy(guardRelease = GuardRelease.Available)

  def canClaim(alreadyOwned: Boolean, owned: Int, activeLimit: Int, pendingLimit: Int): Boolean =
    !alreadyOwned && owned.toLong < activeLimit.toLong + pendingLimit.toLong

}
