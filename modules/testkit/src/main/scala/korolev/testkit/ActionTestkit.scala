package spoonbill.testkit

import spoonbill.action.InvocationBinding
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}

/**
 * Explicit test-only binding factory; it provides no authentication authority.
 */
object ActionTestkit {
  def binding(invocationId: InvocationId, connectionId: ConnectionId): InvocationBinding =
    new InvocationBinding(invocationId, connectionId)
}
