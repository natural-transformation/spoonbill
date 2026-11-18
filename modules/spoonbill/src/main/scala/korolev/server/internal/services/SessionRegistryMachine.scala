package spoonbill.server.internal.services

import scala.collection.immutable.VectorMap

/**
 * Metadata only. Application handles and their asynchronous publication stay in
 * the application table. Storage retirement executes outside the metadata
 * monitor, while the exact attachment owner retains the local fence.
 */
private[spoonbill] object SessionRegistryMachine {
  final case class Slot[C, P](
    owner: Option[C] = None,
    retryRemoval: Boolean = false,
    bootstrap: Option[(Long, P)] = None
  )
  final case class State[K, C, P](
    slots: Map[K, Slot[C, P]] = Map.empty[K, Slot[C, P]],
    bootstrapOrder: VectorMap[K, Unit] = VectorMap.empty[K, Unit]
  )
  private def slot[K, C, P](state: State[K, C, P], key: K): Slot[C, P] =
    state.slots.getOrElse(key, Slot[C, P]())
  private def put[K, C, P](state: State[K, C, P], key: K, value: Slot[C, P]): State[K, C, P] =
    state.copy(slots =
      if (value.owner.isEmpty && !value.retryRemoval && value.bootstrap.isEmpty) state.slots - key
      else state.slots.updated(key, value)
    )

  def claim[K, C, P](state: State[K, C, P], key: K, owner: C): (State[K, C, P], Boolean) = {
    val previous = slot(state, key)
    if (previous.owner.nonEmpty) (state, false)
    else (put(state, key, previous.copy(owner = Some(owner))), true)
  }
  def release[K, C, P](state: State[K, C, P], key: K, owner: C): State[K, C, P] = {
    val previous = slot(state, key)
    if (previous.owner.contains(owner)) put(state, key, previous.copy(owner = None)) else state
  }
  def needsRemoval[K, C, P](state: State[K, C, P], key: K): Boolean =
    state.slots.get(key).exists(_.retryRemoval)
  def removalFailed[K, C, P](state: State[K, C, P], key: K): State[K, C, P] =
    put(state, key, slot(state, key).copy(retryRemoval = true))
  def removalSucceeded[K, C, P](state: State[K, C, P], key: K): State[K, C, P] =
    put(state, key, slot(state, key).copy(retryRemoval = false))

  def remember[K, C, P](state: State[K, C, P], key: K, created: Long, path: P, capacity: Int): State[K, C, P] = {
    require(capacity > 0)
    val previous = slot(state, key)
    val ordered  = if (previous.bootstrap.nonEmpty) state.bootstrapOrder else state.bootstrapOrder.updated(key, ())
    val next     = put(state.copy(bootstrapOrder = ordered), key, previous.copy(bootstrap = Some(created -> path)))
    if (ordered.size <= capacity) next else take(next, ordered.head._1)._1
  }
  def take[K, C, P](state: State[K, C, P], key: K): (State[K, C, P], Option[(Long, P)]) = {
    val previous = slot(state, key)
    if (previous.bootstrap.isEmpty) (state, None)
    else
      (
        put(state.copy(bootstrapOrder = state.bootstrapOrder - key), key, previous.copy(bootstrap = None)),
        previous.bootstrap
      )
  }
  // The original monotonic-duration test is inclusive, and retains Long wrap semantics.
  def fresh(created: Long, now: Long, retentionNanos: Long): Boolean = now - created <= retentionNanos
}
