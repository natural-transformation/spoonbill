package spoonbill.server.internal.services

/**
 * What an attach does with the view that the session registry gave it.
 *
 * A long-polling attach is checked: it names its topic as the owner, and it
 * may only use a view that this topic attached. A view of an older topic is on
 * its way out, because that topic has ended. The attach waits until the view
 * has left the registry and then attaches again, so two views never share the
 * registry entry.
 *
 * An owner can be replaced by a newer one while its attach is under way. Such
 * an attach gets no view, whatever the registry gave it. Not a view to wait
 * for: it may be the newer one, which will not leave. Not a view of its own
 * owner, and not the view that it has just built: that view is on its way
 * out, because its topic has ended, and the frames that it has ready must not
 * answer a request of a transport that is over.
 *
 * A WebSocket attach is not checked. It takes the registered view, as before.
 */
private[services] object LegacyAttachMachine {
  enum Decision {

    /** Use the view. */
    case Attach

    /** The view belongs to another transport. Wait until it has left, then attach again. */
    case AwaitDeparture

    /** The view has left the registry already. Attach again. */
    case Retry

    /** A newer transport has replaced the owner. This attach gets no view. */
    case Superseded
  }

  /**
   * @param checked
   *   the attach names an owner
   * @param superseded
   *   a newer transport has taken the place of that owner
   * @param createdHere
   *   this attach built the view itself
   * @param registered
   *   the registry still holds this very view
   * @param sameOwner
   *   the registered view was attached by the same owner
   */
  def decide(
    checked: Boolean,
    superseded: Boolean,
    createdHere: Boolean,
    registered: Boolean,
    sameOwner: Boolean
  ): Decision =
    if (!checked) Decision.Attach
    else if (superseded) Decision.Superseded
    else if (createdHere) Decision.Attach
    else if (!registered) Decision.Retry
    else if (sameOwner) Decision.Attach
    else Decision.AwaitDeparture

  /**
   * Whether an attach fills the registry, at first and again after a view has
   * left. An attach whose owner was replaced does not: the view that it would
   * build would be a view for nobody.
   *
   * @param checked
   *   the attach names an owner
   * @param superseded
   *   a newer transport has taken the place of that owner
   */
  def mayFill(checked: Boolean, superseded: Boolean): Boolean = !(checked && superseded)

  /**
   * Whether an attach starts again when the fill of the registry that it took
   * part in has failed, and the view of that fill was rolled back.
   *
   * The attach that ran the fill fails: it is its own attach that did not
   * work. An attach that only waited for that view starts again when it names
   * an owner and the page goes on. Then the view was rolled back for a
   * transport that is over, and the waiting attach belongs to a newer one,
   * which is fine. In every other case nothing says that the failure is not
   * the session's own, and the attach fails with it, as it always did.
   *
   * @param checked
   *   the attach names an owner
   * @param ranFill
   *   this attach built the view that was rolled back
   * @param pageGoesOn
   *   the rollback left the stored state in place, because a newer transport
   *   had replaced the one of that view. So no removal runs that a new view
   *   could meet.
   */
  def startsAgain(checked: Boolean, ranFill: Boolean, pageGoesOn: Boolean): Boolean =
    checked && !ranFill && pageGoesOn
}
