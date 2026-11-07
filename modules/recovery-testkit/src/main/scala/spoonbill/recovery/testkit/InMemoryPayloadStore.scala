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
  private val monitor     = new Object
  private var objects     = Map.empty[UUID, SnapshotBytes]
  private var tombstones  = Set.empty[UUID]
  val failWrites          = new AtomicBoolean()
  val failReads           = new AtomicBoolean()
  val failDeletes         = new AtomicBoolean()

  def put(ref: PayloadRef, bytes: SnapshotBytes): F[PayloadWrite] = F.delay {
    if (failWrites.get()) PayloadWrite.Unavailable
    else
      monitor.synchronized {
        if (cleanupBasis == CleanupBasis.DurableRetirementFence && tombstones.contains(ref.id)) PayloadWrite.Retired
        else
          objects.get(ref.id) match {
            case Some(existing) if existing == bytes => PayloadWrite.Durable
            case Some(_)                             => PayloadWrite.Conflict
            case None                                => objects += ref.id -> bytes; PayloadWrite.Durable
          }
      }
  }

  def get(ref: PayloadRef): F[PayloadRead] = F.delay {
    if (failReads.get()) PayloadRead.Unavailable
    else monitor.synchronized(objects.get(ref.id).fold[PayloadRead](PayloadRead.Missing)(PayloadRead.Found.apply))
  }

  def delete(ref: PayloadRef): F[PayloadDelete] = F.delay {
    if (failDeletes.get()) PayloadDelete.Unavailable
    else
      monitor.synchronized {
        objects -= ref.id
        if (cleanupBasis == CleanupBasis.DurableRetirementFence) { tombstones += ref.id; PayloadDelete.FinalDeleted }
        else PayloadDelete.Uncertain
      }
  }

  def contains(ref: PayloadRef): Boolean          = monitor.synchronized(objects.contains(ref.id))
  def objectCount: Int                             = monitor.synchronized(objects.size)
  def corrupt(ref: PayloadRef, bytes: Array[Byte]): Unit =
    monitor.synchronized(objects += ref.id -> SnapshotBytes.copyOf(bytes))
}
