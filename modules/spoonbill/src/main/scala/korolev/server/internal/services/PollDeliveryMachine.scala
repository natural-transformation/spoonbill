package spoonbill.server.internal.services

/**
 * The delivery of one long-polling view to its page.
 *
 * The page names in every request the last frame that it has applied, its
 * cursor `seen`. The view hands out frames numbered from 1, and the delivery
 * answers a cursor `n` only with frame `n + 1`: again if the page did not get
 * it, or for the first time. Nothing pulls ahead. The machine asks for one
 * frame when a poll needs one that is not there, and it keeps the frame that it
 * handed out last until the page confirms it.
 *
 * Every answer with a frame names two numbers: the frame, and the DOM revision
 * after it, which is the number of DOM frames among the frames up to it. The
 * page checks both before it applies the frame.
 *
 * Pure: the interpreter, [[LongPollingDelivery]], keeps the frame itself, the
 * completion handle of each poll that waits, and the one pull of the view. Each
 * waiter is settled once: by a frame, by its timeout or by its cancellation,
 * whichever the machine sees first.
 */
private[spoonbill] object PollDeliveryMachine {

  /** What a frame is, as far as the delivery cares. */
  enum Kind {

    /** A DOM patch. The page counts it, and so its DOM revision. */
    case Dom

    /** A browser command that the view waits for. It can expire. */
    case Command

    /** Any other frame. */
    case Other
  }

  /**
   * @param confirmed
   *   the frames that the page has confirmed
   * @param dom
   *   the DOM frames among them
   * @param kept
   *   the kind of frame `confirmed + 1`, when it was handed out and is not
   *   confirmed
   * @param pulling
   *   a frame is asked for, and has not come
   * @param waiting
   *   the polls that wait for a frame, in the order in which they came
   * @param ended
   *   the view has ended, and no frame will come
   */
  final case class State[I](
    confirmed: Long = 0L,
    dom: Long = 0L,
    kept: Option[Kind] = None,
    pulling: Boolean = false,
    waiting: Vector[I] = Vector.empty[I],
    ended: Boolean = false
  )

  def initial[I]: State[I] = State[I]()

  /** The frames that were handed out: the confirmed ones, and the kept one. */
  def handed[I](state: State[I]): Long = if (state.kept.nonEmpty) state.confirmed + 1L else state.confirmed

  /** The DOM revision after the kept frame, or after the confirmed ones when nothing is kept. */
  private def domAfter[I](state: State[I]): Long =
    if (state.kept.contains(Kind.Dom)) state.dom + 1L else state.dom

  enum Event[I] {

    /**
     * A poll with cursor `seen`.
     *
     * @param keptAwaited
     *   whether the view still waits for the answer to the kept command. It
     *   matters only when the kept frame is a command.
     */
    case Poll(id: I, seen: Long, keptAwaited: Boolean)

    /** The frame that was asked for has come. */
    case Pulled(kind: Kind)

    /** The frame stream of the view has ended. */
    case Ended()

    /** The wait limit of a poll is over. */
    case TimedOut(id: I)

    /** The request of a poll is gone. */
    case Cancelled(id: I)
  }

  enum Result[I] {

    /** This poll gets the reload frame. */
    case Reload()

    /** This poll is older than what the page has confirmed: `410`. */
    case Stale()

    /**
     * This poll gets the kept frame. When `keepAlive` holds, the kept frame is
     * a command that expired, and the poll gets a keep-alive frame under the
     * same number instead. A repeated answer is not progress.
     */
    case Answer(frame: Long, dom: Long, keepAlive: Boolean)

    /**
     * This poll waits for a frame. It is progress: the page confirmed a frame,
     * or it has everything. When `pull` holds, one frame is asked for now.
     */
    case Wait(pull: Boolean)

    /** Every poll in `ids` gets the frame that came. */
    case Answered(ids: Vector[I], frame: Long, dom: Long)

    /** Every poll in `ids` gets the reload frame. */
    case Reloaded(ids: Vector[I])

    /** This poll gets `204`. */
    case NoContent()

    /** Nothing to do: the poll was settled already, or the event changes nothing. */
    case Silent()
  }

  def step[I](state: State[I], event: Event[I]): (State[I], Result[I]) = event match {
    case Event.Poll(id, seen, keptAwaited) =>
      if (state.ended) (state, Result.Reload())
      else if (seen < state.confirmed) (state, Result.Stale())
      else if (seen > handed(state)) (state, Result.Reload())
      else {
        // A cursor one ahead of the confirmed frames confirms the kept one.
        val current =
          if (seen == state.confirmed + 1L) state.copy(confirmed = seen, dom = domAfter(state), kept = None)
          else state
        current.kept match {
          case Some(kind) =>
            (current, Result.Answer(current.confirmed + 1L, domAfter(current), kind == Kind.Command && !keptAwaited))
          case None =>
            val waiting = if (current.waiting.contains(id)) current.waiting else current.waiting :+ id
            if (current.pulling) (current.copy(waiting = waiting), Result.Wait(pull = false))
            else (current.copy(waiting = waiting, pulling = true), Result.Wait(pull = true))
        }
      }
    case Event.Pulled(kind) if state.pulling && !state.ended =>
      val next = state.copy(kept = Some(kind), pulling = false, waiting = Vector.empty[I])
      (next, Result.Answered(state.waiting, next.confirmed + 1L, domAfter(next)))
    case Event.Pulled(_) => (state, Result.Silent())
    case Event.Ended() =>
      if (state.ended) (state, Result.Reloaded(Vector.empty[I]))
      else (state.copy(ended = true, pulling = false, waiting = Vector.empty[I]), Result.Reloaded(state.waiting))
    case Event.TimedOut(id) =>
      if (state.waiting.contains(id)) (state.copy(waiting = state.waiting.filterNot(_ == id)), Result.NoContent())
      else (state, Result.Silent())
    case Event.Cancelled(id) =>
      if (state.waiting.contains(id)) (state.copy(waiting = state.waiting.filterNot(_ == id)), Result.Silent())
      else (state, Result.Silent())
  }

  /** What becomes of a message that the page sends with cursor `seen`. */
  enum Message { case Deliver, Gone }

  /**
   * A message goes to the view unless the view has ended, or the message claims
   * more frames than the view has handed out. Whether its handler runs is
   * decided in the view.
   */
  def message[I](state: State[I], seen: Long): Message =
    if (state.ended || seen > handed(state)) Message.Gone else Message.Deliver
}
