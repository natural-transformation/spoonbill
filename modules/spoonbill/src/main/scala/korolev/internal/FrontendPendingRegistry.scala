package spoonbill.internal

import scala.collection.mutable

/**
 * The shared registry decides admission/retirement; opaque effect handles stay
 * in this monitor-owned interpreter and are invoked only after detachment.
 * Frontend queue limits, rather than this holder, bound action admission.
 */
private[internal] final class FrontendPendingRegistry[K, V] {
  private var registry = PendingRegistryMachine.initial[K]
  private val entries  = mutable.Map.empty[K, V]

  def put(key: K, value: V): Boolean = synchronized {
    val (next, admission) = PendingRegistryMachine.register(
      registry,
      key,
      new PendingRegistryMachine.Token,
      Int.MaxValue,
      PendingRegistryMachine.DuplicatePolicy.Replace
    )
    registry = next
    if (admission == PendingRegistryMachine.Admission.Accepted) { entries.put(key, value); true }
    else false
  }

  def remove(key: K): Option[V] = synchronized {
    val (next, token) = PendingRegistryMachine.take(registry, key, None)
    registry = next
    token.flatMap(_ => entries.remove(key))
  }

  def get(key: K): Option[V] = synchronized(entries.get(key))

  def close(): List[V] = synchronized {
    registry = PendingRegistryMachine.closed[K]
    val pending = entries.values.toList
    entries.clear()
    pending
  }
}
