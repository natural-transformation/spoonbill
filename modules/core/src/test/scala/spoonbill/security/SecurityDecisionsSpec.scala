package spoonbill.security

import Identifiers.*
import Versions.*
import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SecurityDecisionsSpec extends AnyFlatSpec with Matchers {
  private def uuid(n: Long): UUID = new UUID(0L, n)
  private val now                 = Instant.parse("2026-10-05T12:00:00Z")
  private val deadline            = now.plusSeconds(60)
  private val binding = SlotBinding(
    BrowserSessionSlotId.fromUuid(uuid(1)),
    BrowserBindingId.fromUuid(uuid(2)),
    RealmId.fromUuid(uuid(3)),
    CookieNamespace.fromUuid(uuid(4))
  )
  private val sessionA  = AuthSessionId.fromUuid(uuid(5))
  private val sessionB  = AuthSessionId.fromUuid(uuid(6))
  private val emptySlot = BrowserSessionSlot.empty(binding)
  private val pendingA =
    PendingActivation(binding, SlotGeneration.initial, CompletionId.fromUuid(uuid(7)), sessionA, deadline)
  private val pendingB    = pendingA.copy(completionId = CompletionId.fromUuid(uuid(8)), sessionId = sessionB)
  private val invocationA = InvocationId.fromUuid(uuid(9))
  private val invocationB = InvocationId.fromUuid(uuid(10))

  private def accepted[E, A](result: Either[E, A]): A = result match {
    case Right(value) => value
    case Left(error)  => fail(s"Unexpected decision rejection: $error")
  }

  "Browser session slots" should "choose the first activation regardless of preparation or cookie response order" in {
    // Adapter interleaving: B's atomic decision commits first, then A reads that record.
    val activated = accepted(emptySlot.activate(pendingB, now))
    activated.current.map(_.sessionId) shouldBe Some(sessionB)
    activated.generation.toLong shouldBe 1L
    activated.activate(pendingA, now) shouldBe Left(SlotError.StaleGeneration)
    activated.activate(pendingB, now) shouldBe Right(activated)
    // A delayed cookie cannot establish authority even if it overwrites B's browser cookie.
    activated.validateCurrent(binding, sessionA, activated.generation) shouldBe Left(SlotError.SessionNotCurrent)
    activated.validateCurrent(binding, sessionB, activated.generation) shouldBe Right(())
  }

  it should "invalidate prepared completions whether logout wins before or after activation" in {
    val loggedOutBefore = accepted(emptySlot.logout(binding))
    loggedOutBefore.activate(pendingA, now) shouldBe Left(SlotError.StaleGeneration)
    loggedOutBefore.activate(pendingB, now) shouldBe Left(SlotError.StaleGeneration)
    val activated      = accepted(emptySlot.activate(pendingA, now))
    val loggedOutAfter = accepted(activated.logout(binding))
    loggedOutAfter.current shouldBe None
    loggedOutAfter.generation.toLong shouldBe 2L
    loggedOutAfter.activate(pendingA, now) shouldBe Left(SlotError.StaleGeneration)
    loggedOutAfter.validateCurrent(binding, sessionA, activated.generation) shouldBe Left(SlotError.StaleGeneration)
  }

  it should "allow deliberate replacement only at the current generation" in {
    val first       = accepted(emptySlot.activate(pendingA, now))
    val replacement = pendingB.copy(expectedGeneration = first.generation)
    val second      = accepted(first.activate(replacement, now))
    second.generation.toLong shouldBe 2L
    second.activate(pendingA, now) shouldBe Left(SlotError.StaleGeneration)
    second.validateCurrent(binding, sessionA, first.generation) shouldBe Left(SlotError.StaleGeneration)
  }

  it should "reject wrong slot, browser, realm and cookie namespace without changing authority" in {
    val wrongBindings = List(
      binding.copy(slotId = BrowserSessionSlotId.fromUuid(uuid(100))),
      binding.copy(browserBindingId = BrowserBindingId.fromUuid(uuid(100))),
      binding.copy(realmId = RealmId.fromUuid(uuid(100))),
      binding.copy(cookieNamespace = CookieNamespace.fromUuid(uuid(100)))
    )
    wrongBindings.foreach { wrong =>
      emptySlot.activate(pendingA.copy(binding = wrong), now) shouldBe Left(SlotError.BindingMismatch)
      emptySlot.logout(wrong) shouldBe Left(SlotError.BindingMismatch)
    }
    emptySlot.current shouldBe None
  }

  it should "treat the exact completion deadline as expired and never wrap a generation" in {
    emptySlot.activate(pendingA, deadline) shouldBe Left(SlotError.CompletionExpired)
    val maximum   = accepted(SlotGeneration.fromLong(Long.MaxValue))
    val exhausted = emptySlot.copy(generation = maximum)
    exhausted.activate(pendingA.copy(expectedGeneration = maximum), now) shouldBe Left(SlotError.GenerationExhausted)
    exhausted.logout(binding) shouldBe Left(SlotError.GenerationExhausted)
    SlotGeneration.fromLong(-1) shouldBe Left(VersionError.Negative)
  }

  "View ownership" should "fence a live previous owner after transfer without changing authentication generations" in {
    val viewId   = ViewSessionId.fromUuid(uuid(11))
    val ownerA   = ViewOwnerId.fromUuid(uuid(12))
    val ownerB   = ViewOwnerId.fromUuid(uuid(13))
    val first    = accepted(ViewOwnership.unowned(viewId).acquire(ViewOwnershipEpoch.initial, ownerA))
    val oldFence = accepted(first.fence)
    val second   = accepted(first.acquire(first.epoch, ownerB))
    second.validate(oldFence) shouldBe Left(OwnershipError.StaleEpoch)
    second.release(oldFence) shouldBe Left(OwnershipError.StaleEpoch)
    second.acquire(first.epoch, ownerA) shouldBe Left(OwnershipError.StaleEpoch)
    second.validate(accepted(second.fence)) shouldBe Right(())
    second.epoch.toLong shouldBe 2L
    emptySlot.generation shouldBe SlotGeneration.initial
  }

  it should "invalidate a released fence and reject wrong owners and views" in {
    val viewId  = ViewSessionId.fromUuid(uuid(11))
    val unowned = ViewOwnership.unowned(viewId)
    unowned.fence shouldBe Left(OwnershipError.Unowned)
    val owned = accepted(unowned.acquire(unowned.epoch, ViewOwnerId.fromUuid(uuid(12))))
    val fence = accepted(owned.fence)
    owned.validate(fence.copy(ownerId = ViewOwnerId.fromUuid(uuid(13)))) shouldBe Left(OwnershipError.WrongOwner)
    owned.validate(fence.copy(viewId = ViewSessionId.fromUuid(uuid(14)))) shouldBe Left(OwnershipError.WrongView)
    val released = accepted(owned.release(fence))
    released.fence shouldBe Left(OwnershipError.Unowned)
    released.validate(fence) shouldBe Left(OwnershipError.StaleEpoch)
  }

  it should "fail closed when the epoch counter is exhausted" in {
    val record =
      ViewOwnership(ViewSessionId.fromUuid(uuid(11)), accepted(ViewOwnershipEpoch.fromLong(Long.MaxValue)), None)
    record.acquire(record.epoch, ViewOwnerId.fromUuid(uuid(12))) shouldBe Left(OwnershipError.EpochExhausted)
  }

  private val operationBinding = OperationBinding(
    SubjectId.fromUuid(uuid(20)),
    binding.realmId,
    sessionA,
    SecurityGeneration.initial,
    OperationPurpose.fromUuid(uuid(21)),
    ResourceScope.fromUuid(uuid(22))
  )
  private val grant = OperationAuthorization(
    OperationAuthorizationId.fromUuid(uuid(23)),
    operationBinding,
    deadline,
    ReservationPolicy.ReleaseAfterDefiniteFailure,
    OperationAuthorizationState.Available
  )

  "Operation authorizations" should "admit only one invocation and distinguish retries from new execution" in {
    val reserved = accepted(grant.reserve(operationBinding, invocationA, now))
    reserved.decision shouldBe ReservationDecision.Acquired
    reserved.authorization.reserve(operationBinding, invocationB, now) shouldBe Left(
      OperationAuthorizationError.CompetingInvocation
    )
    accepted(
      reserved.authorization.reserve(operationBinding, invocationA, now)
    ).decision shouldBe ReservationDecision.InProgress
    val committed = accepted(reserved.authorization.commit(invocationA))
    accepted(committed.reserve(operationBinding, invocationA, now)).decision shouldBe ReservationDecision.Committed
    committed.commit(invocationA) shouldBe Right(committed)
    committed.reserve(operationBinding, invocationB, now) shouldBe Left(OperationAuthorizationError.CompetingInvocation)
    committed.release(invocationA, now) shouldBe Left(OperationAuthorizationError.AlreadyCommitted)
  }

  it should "check every operation binding and enforce the exact expiry boundary" in {
    val wrongBindings = List(
      operationBinding.copy(subjectId = SubjectId.fromUuid(uuid(100))),
      operationBinding.copy(realmId = RealmId.fromUuid(uuid(100))),
      operationBinding.copy(sessionId = sessionB),
      operationBinding.copy(securityGeneration = accepted(SecurityGeneration.initial.next)),
      operationBinding.copy(purpose = OperationPurpose.fromUuid(uuid(100))),
      operationBinding.copy(resourceScope = ResourceScope.fromUuid(uuid(100)))
    )
    wrongBindings.foreach { wrong =>
      grant.reserve(wrong, invocationA, now) shouldBe Left(OperationAuthorizationError.BindingMismatch)
    }
    grant.reserve(operationBinding, invocationA, deadline) shouldBe Left(OperationAuthorizationError.Expired)
    grant.commit(invocationA) shouldBe Left(OperationAuthorizationError.NotReserved)
  }

  it should "keep unknown commits unavailable until the same invocation is reconciled" in {
    val reserved = accepted(grant.reserve(operationBinding, invocationA, now)).authorization
    val unknown  = accepted(reserved.markUnknown(invocationA))
    unknown.release(invocationA, now) shouldBe Left(OperationAuthorizationError.ReconciliationRequired)
    unknown.reserve(operationBinding, invocationB, now) shouldBe Left(OperationAuthorizationError.CompetingInvocation)
    accepted(unknown.reserve(operationBinding, invocationA, deadline)).decision shouldBe ReservationDecision.Unknown
    unknown.commit(invocationB) shouldBe Left(OperationAuthorizationError.CompetingInvocation)
    unknown.reconcileNotCommitted(invocationB, now) shouldBe Left(OperationAuthorizationError.CompetingInvocation)
    val committed = accepted(unknown.commit(invocationA))
    committed.state shouldBe OperationAuthorizationState.Committed(invocationA)
    val released = accepted(unknown.reconcileNotCommitted(invocationA, now))
    accepted(released.reserve(operationBinding, invocationB, now)).decision shouldBe ReservationDecision.Acquired
  }

  it should "release only definite failures and burn consume-on-attempt grants" in {
    val reserved = accepted(grant.reserve(operationBinding, invocationA, now)).authorization
    reserved.release(invocationB, now) shouldBe Left(OperationAuthorizationError.CompetingInvocation)
    accepted(reserved.release(invocationA, now)).state shouldBe OperationAuthorizationState.Available
    val consumed = accepted(
      grant.copy(policy = ReservationPolicy.ConsumeOnAttempt).reserve(operationBinding, invocationA, now)
    ).authorization
    val burned = accepted(consumed.release(invocationA, now))
    burned.state shouldBe OperationAuthorizationState.Invalidated(GrantInvalidation.Burned)
    burned.reserve(operationBinding, invocationB, now) shouldBe Left(OperationAuthorizationError.Burned)
  }

  it should "never revive expired or revoked grants through release or reconciliation" in {
    val reserved = accepted(grant.reserve(operationBinding, invocationA, now)).authorization
    accepted(reserved.release(invocationA, deadline)).state shouldBe OperationAuthorizationState.Invalidated(
      GrantInvalidation.Expired
    )
    accepted(reserved.revoke.release(invocationA, now)).state shouldBe OperationAuthorizationState.Invalidated(
      GrantInvalidation.Revoked
    )
    val unknown = accepted(reserved.markUnknown(invocationA)).revoke
    accepted(unknown.reconcileNotCommitted(invocationA, now)).state shouldBe OperationAuthorizationState.Invalidated(
      GrantInvalidation.Revoked
    )
    // Invalidation cannot undo a domain transaction that already committed.
    accepted(unknown.commit(invocationA)).state shouldBe OperationAuthorizationState.Committed(invocationA)
    grant.revoke.reserve(operationBinding, invocationA, now) shouldBe Left(OperationAuthorizationError.Revoked)
  }
}
