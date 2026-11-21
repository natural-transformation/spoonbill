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
 * An owner can be replaced by a newer one while its attach is under way. Then
 * the view of another owner may be the newer one, which will not leave. This
 * decision does not look at that. The session service does, around it: the
 * wait ends when the owner is replaced, and an attach looks at its owner
 * before every start. A replaced owner gets no view then, and builds none.
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
