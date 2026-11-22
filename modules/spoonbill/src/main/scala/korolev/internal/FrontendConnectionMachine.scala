package spoonbill.internal

/**
 * Connection authority metadata only. Publication and the final admission
 * checks retain their existing interpreter monitor and effect boundaries.
 */
private[internal] object FrontendConnectionMachine {
  enum Status   { case Live, Replacing, Closed, ClosedReplacing    }
  enum View     { case Unpublished; case Published(revision: Long) }
  enum Revision { case Current, Obsolete, Denied                   }
  final case class State(status: Status, view: View, navigation: Long, departure: Long, handledDeparture: Long)
  val initial: State   = State(Status.Live, View.Unpublished, 0L, 0L, 0L)
  val maxCounter: Long = 9007199254740991L

  def closed(state: State): Boolean    = state.status == Status.Closed || state.status == Status.ClosedReplacing
  def replacing(state: State): Boolean = state.status == Status.Replacing || state.status == Status.ClosedReplacing
  def live(state: State): Boolean      = state.status == Status.Live

  def close(state: State): State = state.status match {
    case Status.Live      => state.copy(status = Status.Closed)
    case Status.Replacing => state.copy(status = Status.ClosedReplacing)
    case _                => state
  }

  // The interpreter has already checked openness before this reservation. Keep
  // the independent replacement claim if closure wins between those two steps.
  def replace(state: State): Option[State] = state.status match {
    case Status.Live   => Some(state.copy(status = Status.Replacing))
    case Status.Closed => Some(state.copy(status = Status.ClosedReplacing))
    case _             => None
  }

  def revision(state: State, expected: Long): Revision = state.view match {
    case View.Unpublished                                           => Revision.Denied
    case View.Published(value) if expected < 0L || expected > value => Revision.Denied
    case View.Published(value) if expected < value                  => Revision.Obsolete
    case _                                                          => Revision.Current
  }

  /**
   * Browser input (a DOM event or a custom callback) of a view that resumes
   * must name the revision that its page shows: input that names none could
   * run a handler of any display, so it runs nothing. Other input that names
   * no revision is not compared.
   */
  def unrevised(resumes: Boolean, browserInput: Boolean, expected: Option[Long]): Boolean =
    resumes && browserInput && expected.isEmpty

  /**
   * An unguarded view runs a user action directly, except a browser callback
   * of a view that resumes: that one is checked against the published
   * revision right before it runs, as a DOM event is. History and programmatic
   * transitions always run.
   */
  def checkedBeforeRun(resumes: Boolean, browserInput: Boolean): Boolean = resumes && browserInput

  /**
   * Whether a view closes when its input fails: a message that it cannot read,
   * or a reply that it cannot take. A guarded view closes. So does a view that
   * resumes: its polls would keep it alive while it handles nothing, and its
   * page would stay deaf; once it closes, its page is told to reload. An
   * unguarded WebSocket view keeps the failure in its input, as on `main`.
   */
  def closesOnFailedInput(guarded: Boolean, resumes: Boolean): Boolean = guarded || resumes

  /**
   * Validate before offering output; update publication only after acceptance.
   */
  def canPublish(state: State, reset: Boolean): Boolean =
    if (!live(state)) false
    else
      (state.view, reset) match {
        case (View.Unpublished, true)          => true
        case (View.Published(previous), false) => previous < maxCounter
        case _                                 => false
      }

  /**
   * The offer may resume a consumer inline. Apply the old post-offer update to
   * the latest state, preserving closure, fences and any reentrant revision.
   */
  def published(state: State, reset: Boolean): State = state.view match {
    case View.Unpublished           => state.copy(view = View.Published(if (reset) 0L else 1L))
    case View.Published(_) if reset => state
    case View.Published(value)      => state.copy(view = View.Published(value + 1L))
  }

  def navigate(state: State): State = state.copy(navigation = state.navigation + 1L)
  def depart(state: State, counter: Long): State =
    if (counter <= state.departure) state else state.copy(departure = counter)
  def handleDeparture(state: State, counter: Long): Option[State] =
    Option.when(counter > state.handledDeparture)(state.copy(handledDeparture = counter))
}
