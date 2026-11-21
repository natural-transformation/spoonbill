package spoonbill.server.internal.services

/**
 * What an attach does with the view that the session registry gave it.
 *
 * A long-polling attach is checked: it names its topic as the owner, and it
 * may only use a view that this topic attached. A view of an older topic is on
 * its way out, because that topic has ended. The attach waits until the view
 * has left the registry and then attaches again, so the new view never shares
 * the registry entry or the stored state with the old one.
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
  }

  /**
   * @param checked
   *   the attach names an owner
   * @param createdHere
   *   this attach built the view itself
   * @param registered
   *   the registry still holds this very view
   * @param sameOwner
   *   the registered view was attached by the same owner
   */
  def decide(checked: Boolean, createdHere: Boolean, registered: Boolean, sameOwner: Boolean): Decision =
    if (!checked || createdHere) Decision.Attach
    else if (!registered) Decision.Retry
    else if (sameOwner) Decision.Attach
    else Decision.AwaitDeparture
}
