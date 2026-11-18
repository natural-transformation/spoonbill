package spoonbill.recovery.testkit

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.effect.Effect
import spoonbill.recovery.*

/**
 * A process-local payload adapter for law suites. With
 * CleanupBasis.DurableRetirementFence a delete leaves a tombstone and every
 * later put of that reference fails as Retired under the same monitor, so
 * retirement and write admission serialize at the store. With
 * CleanupBasis.Unproven the bytes are removed but every delete answers
 * Uncertain, as a store without a fence must. Failure switches let binding
 * tests observe Unavailable outcomes; corrupt replaces stored bytes.
 */
final class InMemoryPayloadStore[F[_]](val cleanupBasis: CleanupBasis = CleanupBasis.DurableRetirementFence)(using
  F: Effect[F]
) extends SnapshotPayloadStore[F] {
  require(cleanupBasis != CleanupBasis.VerifiedQuiescence, "An in-process map cannot prove write quiescence")
  private val monitor = new Object
  private var slots   = Map.empty[UUID, PayloadStoreMachine.Slot]
  val failWrites      = new AtomicBoolean()
  val failReads       = new AtomicBoolean()
  val failDeletes     = new AtomicBoolean()

  def put(ref: PayloadRef, bytes: SnapshotBytes): F[PayloadWrite] = F.delay {
    if (failWrites.get()) PayloadWrite.Unavailable
    else
      monitor.synchronized {
        val (slot, result) = PayloadStoreMachine.put(slots.get(ref.id), bytes)
        slots += ref.id -> slot
        result
      }
  }

  def get(ref: PayloadRef): F[PayloadRead] = F.delay {
    if (failReads.get()) PayloadRead.Unavailable
    else monitor.synchronized(PayloadStoreMachine.read(slots.get(ref.id)))
  }

  def delete(ref: PayloadRef): F[PayloadDelete] = F.delay {
    if (failDeletes.get()) PayloadDelete.Unavailable
    else
      monitor.synchronized {
        val (slot, result) = PayloadStoreMachine.delete(cleanupBasis == CleanupBasis.DurableRetirementFence)
        slots = slot.fold(slots - ref.id)(value => slots.updated(ref.id, value))
        result
      }
  }

  def contains(ref: PayloadRef): Boolean =
    monitor.synchronized(slots.get(ref.id).flatMap(PayloadStoreMachine.bytes).nonEmpty)
  def objectCount: Int = monitor.synchronized(slots.valuesIterator.count(PayloadStoreMachine.bytes(_).nonEmpty))
  def corrupt(ref: PayloadRef, bytes: Array[Byte]): Unit =
    monitor.synchronized {
      slots += ref.id -> PayloadStoreMachine.corrupt(slots.get(ref.id), SnapshotBytes.copyOf(bytes))
    }
}
