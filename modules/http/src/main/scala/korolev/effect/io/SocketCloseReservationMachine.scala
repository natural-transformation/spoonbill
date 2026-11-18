package spoonbill.effect.io

/** Permit ownership; worker submission and completion notification stay outside. */
private[spoonbill] object SocketCloseReservationMachine {
  enum State {
    case Reserved, ReservedAfterConnection, Dispatched, AwaitingConnection, AwaitingNotification, Released
  }
  enum Event { case Dispatch, Cancel, ConnectionFinished, NotificationFinished }
  enum Command { case None, Submit, Release }

  val initial: State = State.Reserved

  def step(state: State, event: Event): (State, Command) = (state, event) match {
    case (State.Reserved, Event.Dispatch) => (State.Dispatched, Command.Submit)
    case (State.ReservedAfterConnection, Event.Dispatch) => (State.AwaitingNotification, Command.Submit)
    case (State.Reserved | State.ReservedAfterConnection, Event.Cancel) => (State.Released, Command.Release)
    case (State.Reserved, Event.ConnectionFinished) => (State.ReservedAfterConnection, Command.None)
    case (State.Dispatched, Event.ConnectionFinished) => (State.AwaitingNotification, Command.None)
    case (State.Dispatched, Event.NotificationFinished) => (State.AwaitingConnection, Command.None)
    case (State.AwaitingConnection, Event.ConnectionFinished) => (State.Released, Command.Release)
    case (State.AwaitingNotification, Event.NotificationFinished) => (State.Released, Command.Release)
    case _ => (state, Command.None)
  }
}
