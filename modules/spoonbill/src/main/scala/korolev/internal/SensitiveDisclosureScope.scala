package spoonbill.internal

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import spoonbill.effect.LateHandleMachine
import spoonbill.sensitive.{SensitiveDisclosure, SensitivePayload}
import spoonbill.server.SessionAccessDenied

/** Pending action outcomes belong to the physical connection, even while an
  * action's final policy check is stalled. Only metadata is closed over by timers.
  * Transfer removes the holder here; the delivery runtime then owns its lifetime.
  */
private[spoonbill] final class SensitiveDisclosureScope(deadlines: Frontend.RpcDeadlineScheduler) {
  private final case class Cancellation(state: LateHandleMachine.State, handle: Option[() => Unit])
  private final class Entry(val disclosure: SensitiveDisclosure) {
    val token = new PendingRegistryMachine.Token
    var cancellation = Cancellation(LateHandleMachine.State.Waiting, None) // ALLOW-VAR: protected by entries monitor.
  }
  private val entries = mutable.Map.empty[UUID, Entry]
  private var registry = PendingRegistryMachine.initial[UUID] // ALLOW-VAR: protected by entries monitor.

  def pendingCount: Int = entries.synchronized(PendingRegistryMachine.size(registry))

  def create(payload: SensitivePayload, owner: SensitiveDisclosure.Owner): SensitiveDisclosure = {
    val id = UUID.randomUUID()
    val disclosure = SensitiveDisclosure.once(payload, owner, () => release(id))
    val entry = new Entry(disclosure)
    try {
      entries.synchronized {
        val (next, admission) = PendingRegistryMachine.register(registry, id, entry.token, 4,
          PendingRegistryMachine.DuplicatePolicy.Replace)
        if (admission != PendingRegistryMachine.Admission.Accepted) throw new SessionAccessDenied
        registry = next
        entries.put(id, entry)
      }
      val cancel = deadlines.schedule(10.seconds)(() => expire(id))
      val installed = entries.synchronized {
        val state = if (PendingRegistryMachine.contains(registry, id, entry.token)) entry.cancellation.state
        else LateHandleMachine.State.Finished
        val (next, command) = LateHandleMachine.step(state, LateHandleMachine.Event.Install)
        if (command == LateHandleMachine.Command.StoreHandle) {
          entry.cancellation = Cancellation(next, Some(cancel)); true
        } else false
      }
      if (!installed) cancel()
      disclosure
    } catch {
      case NonFatal(error) => disclosure.discard(); throw error
    }
  }

  private def release(id: UUID): Unit = {
    val cancellation = entries.synchronized {
      val (next, taken) = PendingRegistryMachine.take(registry, id, None)
      registry = next
      taken.flatMap(_ => entries.remove(id)).flatMap { entry =>
        val before = entry.cancellation
        val (next, command) = LateHandleMachine.step(before.state, LateHandleMachine.Event.Finish)
        entry.cancellation = Cancellation(next, None)
        if (command == LateHandleMachine.Command.CancelAndFinish) before.handle else None
      }
    }
    // Cleanup of other holders must not be interrupted by a scheduler adapter.
    cancellation.foreach(cancel => try cancel() catch { case NonFatal(_) => () })
  }

  private def expire(id: UUID): Unit = {
    val disclosure = entries.synchronized(entries.get(id).map(_.disclosure))
    disclosure.foreach(_.discard())
  }

  def close(): Unit = {
    val pending = entries.synchronized {
      registry = PendingRegistryMachine.seal(registry)
      entries.values.map(_.disclosure).toList
    }
    pending.foreach(_.discard())
  }
}
