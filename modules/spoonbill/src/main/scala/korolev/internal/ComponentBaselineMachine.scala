package spoonbill.internal

/**
 * Baseline trust and loader admission belong to one metadata value. Parameters
 * are ordinary data with a separate publication boundary; their representation
 * adds no wrapper to the render path. No application effect belongs here.
 */
private[spoonbill] object ComponentBaselineMachine {
  enum State {
    case Ready, NeedsBaseline, LoadingInitial, RefreshingReady, RefreshingBaseline
    case SuppressedReady, SuppressedBaseline
  }
  enum Load { case Initial, Refresh }

  def initial(reused: Boolean): State = if (reused) State.NeedsBaseline else State.Ready
  def hasBaseline(state: State): Boolean = state match {
    case State.Ready | State.LoadingInitial | State.RefreshingReady | State.SuppressedReady => true
    case _                                                                                  => false
  }
  def acceptsLoad(state: State): Boolean = state == State.Ready || state == State.NeedsBaseline
  def begin(state: State, load: Load): State = load match {
    case Load.Initial if state == State.Ready         => State.LoadingInitial
    case Load.Refresh if state == State.Ready         => State.RefreshingReady
    case Load.Refresh if state == State.NeedsBaseline => State.RefreshingBaseline
    case _                                            => state
  }
  def written: State = State.Ready
  def failed(state: State, ownerOpen: Boolean): State =
    if (ownerOpen) { if (hasBaseline(state)) State.Ready else State.NeedsBaseline }
    else
      state match {
        case State.LoadingInitial | State.RefreshingReady => State.SuppressedReady
        case State.RefreshingBaseline                     => State.SuppressedBaseline
        case _                                            => state
      }

  private object Unseen
  opaque type Parameters[P] = Any
  def emptyParameters[P]: Parameters[P]    = Unseen
  def remember[P](value: P): Parameters[P] = value
  def parametersDiffer[P](previous: Parameters[P], current: P): Boolean = {
    // Preserve the old first-render comparison against null, including when
    // null itself is a legitimate parameter value, without encoding absence as null.
    val value = if (Unseen eq previous.asInstanceOf[AnyRef]) null else previous
    value != current
  }
}
