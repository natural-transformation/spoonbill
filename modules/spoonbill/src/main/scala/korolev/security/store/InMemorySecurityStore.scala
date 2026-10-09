package spoonbill.security.store

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import spoonbill.effect.Effect
import spoonbill.security.*
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*

/**
 * Bounded, process-local reference adapter. All decisions and their record
 * updates are linearized in one short critical section. Effects are deferred
 * through Effect.delay; application callbacks never run inside the monitor.
 *
 * NOT durable or distributed. Restart loses all records, including revocation
 * and deduplication. It stores no browser credentials/session-delivery records,
 * and cannot atomically commit an external business mutation. Its grant records
 * therefore model reservation/reconciliation, not a production transaction.
 */
final class InMemorySecurityStore[F[_]] private (limits: StoreLimits)(using F: Effect[F])
    extends BrowserSessionSlotStore[F]
    with ViewOwnershipStore[F]
    with OperationAuthorizationStore[F] {
  private case class Records(
    slots: Map[BrowserSessionSlotId, BrowserSessionSlot] = Map.empty,
    views: Map[ViewSessionId, ViewRecord] = Map.empty,
    grants: Map[OperationAuthorizationId, OperationAuthorization] = Map.empty,
    invocations: Map[InvocationId, InvocationRecord] = Map.empty
  )

  private val monitor = new Object
  private val records = new AtomicReference(Records())

  private def atomic[A](operation: => Either[StoreError, A]): F[Either[StoreError, A]] =
    F.delay(monitor.synchronized(operation))

  def createSlot(binding: SlotBinding): F[Either[StoreError, BrowserSessionSlot]] = atomic {
    val before = records.get()
    before.slots.get(binding.slotId) match {
      case Some(existing) if existing.binding == binding => Right(existing)
      case Some(_)                                       => Left(StoreError.SlotRejected(SlotError.BindingMismatch))
      case None if before.slots.size >= limits.maxSlots  => Left(StoreError.CapacityExceeded(RecordKind.Slot))
      case None =>
        val created = BrowserSessionSlot.empty(binding)
        records.set(before.copy(slots = before.slots.updated(binding.slotId, created)))
        Right(created)
    }
  }

  def readSlot(id: BrowserSessionSlotId): F[Either[StoreError, BrowserSessionSlot]] = atomic {
    records.get().slots.get(id).toRight(StoreError.Missing(RecordKind.Slot))
  }

  def activate(pending: PendingActivation, now: Instant): F[Either[StoreError, BrowserSessionSlot]] =
    updateSlot(pending.binding.slotId)(_.activate(pending, now))

  def logout(binding: SlotBinding): F[Either[StoreError, BrowserSessionSlot]] =
    updateSlot(binding.slotId)(_.logout(binding))

  def validateCurrent(
    binding: SlotBinding,
    sessionId: AuthSessionId,
    generation: SlotGeneration
  ): F[Either[StoreError, Unit]] = atomic {
    records
      .get()
      .slots
      .get(binding.slotId)
      .toRight(StoreError.Missing(RecordKind.Slot))
      .flatMap(_.validateCurrent(binding, sessionId, generation).left.map(StoreError.SlotRejected.apply))
  }

  private def updateSlot(id: BrowserSessionSlotId)(
    decision: BrowserSessionSlot => Either[SlotError, BrowserSessionSlot]
  ): F[Either[StoreError, BrowserSessionSlot]] = atomic {
    val before = records.get()
    before.slots
      .get(id)
      .toRight(StoreError.Missing(RecordKind.Slot))
      .flatMap(slot => decision(slot).left.map(StoreError.SlotRejected.apply))
      .map { updated =>
        records.set(before.copy(slots = before.slots.updated(id, updated)))
        updated
      }
  }

  def createView(id: ViewSessionId): F[Either[StoreError, ViewRecord]] = atomic {
    val before = records.get()
    before.views.get(id) match {
      case Some(existing)                               => Right(existing)
      case None if before.views.size >= limits.maxViews => Left(StoreError.CapacityExceeded(RecordKind.View))
      case None =>
        val created = ViewRecord(ViewOwnership.unowned(id), ViewRevision.initial)
        records.set(before.copy(views = before.views.updated(id, created)))
        Right(created)
    }
  }

  def readView(id: ViewSessionId): F[Either[StoreError, ViewRecord]] = atomic {
    records.get().views.get(id).toRight(StoreError.Missing(RecordKind.View))
  }

  def acquireView(
    id: ViewSessionId,
    expectedEpoch: ViewOwnershipEpoch,
    owner: ViewOwnerId
  ): F[Either[StoreError, ViewFence]] = atomic {
    val before = records.get()
    before.views.get(id).toRight(StoreError.Missing(RecordKind.View)).flatMap { view =>
      view.ownership.acquire(expectedEpoch, owner).left.map(StoreError.OwnershipRejected.apply).flatMap { ownership =>
        ownership.fence.left.map(StoreError.OwnershipRejected.apply).map { fence =>
          records.set(before.copy(views = before.views.updated(id, view.copy(ownership = ownership))))
          fence
        }
      }
    }
  }

  def releaseView(fence: ViewFence): F[Either[StoreError, ViewRecord]] = atomic {
    val before = records.get()
    before.views.get(fence.viewId).toRight(StoreError.Missing(RecordKind.View)).flatMap { view =>
      view.ownership.release(fence).left.map(StoreError.OwnershipRejected.apply).map { ownership =>
        val updated = view.copy(ownership = ownership)
        records.set(before.copy(views = before.views.updated(fence.viewId, updated)))
        updated
      }
    }
  }

  def validateFence(fence: ViewFence): F[Either[StoreError, Unit]] = atomic {
    records
      .get()
      .views
      .get(fence.viewId)
      .toRight(StoreError.Missing(RecordKind.View))
      .flatMap(_.ownership.validate(fence).left.map(StoreError.OwnershipRejected.apply))
  }

  def advanceRevision(fence: ViewFence, expected: ViewRevision): F[Either[StoreError, ViewRevision]] = atomic {
    val before = records.get()
    before.views.get(fence.viewId).toRight(StoreError.Missing(RecordKind.View)).flatMap { view =>
      view.ownership.validate(fence).left.map(StoreError.OwnershipRejected.apply).flatMap { _ =>
        if (view.revision != expected) Left(StoreError.RevisionMismatch)
        else
          view.revision.next.left.map(_ => StoreError.RevisionExhausted).map { next =>
            records.set(before.copy(views = before.views.updated(fence.viewId, view.copy(revision = next))))
            next
          }
      }
    }
  }

  def issueGrant(definition: GrantDefinition): F[Either[StoreError, OperationAuthorization]] = atomic {
    val before = records.get()
    val initial = OperationAuthorization(
      definition.id,
      definition.binding,
      definition.expiresAt,
      definition.policy,
      OperationAuthorizationState.Available
    )
    before.grants.get(definition.id) match {
      case Some(existing) if existing.copy(state = OperationAuthorizationState.Available) == initial => Right(existing)
      case Some(_)                                                                                   => Left(StoreError.ConflictingDefinition(RecordKind.Grant))
      case None if before.grants.size >= limits.maxGrants                                            => Left(StoreError.CapacityExceeded(RecordKind.Grant))
      case None =>
        records.set(before.copy(grants = before.grants.updated(definition.id, initial)))
        Right(initial)
    }
  }

  def readGrant(id: OperationAuthorizationId): F[Either[StoreError, OperationAuthorization]] = atomic {
    records.get().grants.get(id).toRight(StoreError.Missing(RecordKind.Grant))
  }

  def reserve(
    id: OperationAuthorizationId,
    binding: OperationBinding,
    invocationId: InvocationId,
    now: Instant
  ): F[Either[StoreError, ReservationOutcome]] = atomic {
    val before = records.get()
    before.grants.get(id).toRight(StoreError.Missing(RecordKind.Grant)).flatMap { grant =>
      if (grant.binding != binding) Left(StoreError.GrantRejected(OperationAuthorizationError.BindingMismatch))
      else
        before.invocations.get(invocationId) match {
          case Some(existing) if existing.grantId != id || existing.binding != binding =>
            Left(StoreError.InvocationConflict)
          case Some(existing) =>
            Right(existing.status match {
              case InvocationStatus.InProgress   => ReservationOutcome.InProgress
              case InvocationStatus.Unknown      => ReservationOutcome.Unknown
              case InvocationStatus.Committed    => ReservationOutcome.Committed
              case InvocationStatus.NotCommitted => ReservationOutcome.NotCommitted
            })
          case None =>
            grant.reserve(binding, invocationId, now).left.map(StoreError.GrantRejected.apply).flatMap { reserved =>
              if (before.invocations.size >= limits.maxInvocations)
                Left(StoreError.CapacityExceeded(RecordKind.Invocation))
              else if (reserved.decision != ReservationDecision.Acquired) Left(StoreError.InvocationConflict)
              else {
                val invocation = InvocationRecord(invocationId, id, binding, InvocationStatus.InProgress)
                records.set(
                  before.copy(
                    grants = before.grants.updated(id, reserved.authorization),
                    invocations = before.invocations.updated(invocationId, invocation)
                  )
                )
                Right(ReservationOutcome.Acquired)
              }
            }
        }
    }
  }

  def readInvocation(id: InvocationId): F[Either[StoreError, InvocationRecord]] = atomic {
    records.get().invocations.get(id).toRight(StoreError.Missing(RecordKind.Invocation))
  }

  def markUnknown(id: InvocationId): F[Either[StoreError, InvocationRecord]] =
    settle(id, InvocationStatus.Unknown)(_.markUnknown(id))

  def recordCommitted(id: InvocationId): F[Either[StoreError, InvocationRecord]] =
    settle(id, InvocationStatus.Committed)(_.commit(id))

  def releaseAfterDefiniteFailure(id: InvocationId, now: Instant): F[Either[StoreError, InvocationRecord]] =
    settle(id, InvocationStatus.NotCommitted)(_.release(id, now))

  def reconcileNotCommitted(id: InvocationId, now: Instant): F[Either[StoreError, InvocationRecord]] =
    settle(id, InvocationStatus.NotCommitted)(_.reconcileNotCommitted(id, now))

  private def settle(id: InvocationId, status: InvocationStatus)(
    decision: OperationAuthorization => Either[OperationAuthorizationError, OperationAuthorization]
  ): F[Either[StoreError, InvocationRecord]] = atomic {
    val before = records.get()
    before.invocations.get(id).toRight(StoreError.Missing(RecordKind.Invocation)).flatMap { invocation =>
      if (invocation.status == status) Right(invocation)
      else if (invocation.status == InvocationStatus.Committed || invocation.status == InvocationStatus.NotCommitted)
        Left(StoreError.OutcomeConflict)
      else
        before.grants.get(invocation.grantId).toRight(StoreError.Missing(RecordKind.Grant)).flatMap { grant =>
          decision(grant).left.map(StoreError.GrantRejected.apply).map { updated =>
            val completed = invocation.copy(status = status)
            records.set(
              before.copy(
                grants = before.grants.updated(invocation.grantId, updated),
                invocations = before.invocations.updated(id, completed)
              )
            )
            completed
          }
        }
    }
  }

  def revokeGrant(id: OperationAuthorizationId): F[Either[StoreError, OperationAuthorization]] = atomic {
    val before = records.get()
    before.grants.get(id).toRight(StoreError.Missing(RecordKind.Grant)).map { grant =>
      val revoked = grant.revoke
      records.set(before.copy(grants = before.grants.updated(id, revoked)))
      revoked
    }
  }
}

object InMemorySecurityStore {
  def create[F[_]: Effect](limits: StoreLimits = StoreLimits.default): F[InMemorySecurityStore[F]] =
    Effect[F].delay(new InMemorySecurityStore[F](limits))
}
