package spoonbill.server.internal.services

/**
 * One registered legacy view, as the attaches of its session see it. The
 * registry itself, the error of a failed retirement and the handles of the
 * attaches that wait belong to the interpreter, [[LegacyViews]].
 *
 * A view is registered until its retirement has taken it out of the registry,
 * or has failed. An attach of another transport can wait for that. From then
 * on the view is awaited: the page goes on, so its retirement leaves the
 * stored state to the next view.
 */
private[services] object LegacyViewMachine {
  enum State {

    /** In the registry. `awaited`: an attach waits, or has waited, for it to leave. */
    case Registered(awaited: Boolean)

    /** The retirement has taken the view out of the registry. */
    case Left

    /** The retirement failed. The view stays in the registry and will not leave. */
    case Stuck
  }
  enum Event { case Waited, Departed, Failed }
  enum Command {

    /** Tell every attach that waits, now and later. */
    case Publish
    case Ignore
  }

  val initial: State = State.Registered(awaited = false)

  def step(state: State, event: Event): (State, Command) = (state, event) match {
    case (State.Registered(_), Event.Waited)   => (State.Registered(awaited = true), Command.Ignore)
    case (State.Registered(_), Event.Departed) => (State.Left, Command.Publish)
    case (State.Registered(_), Event.Failed)   => (State.Stuck, Command.Publish)
    // The first end is final. A late event changes nothing and tells nobody.
    case _ => (state, Command.Ignore)
  }

  def awaited(state: State): Boolean = state == State.Registered(awaited = true)
}
