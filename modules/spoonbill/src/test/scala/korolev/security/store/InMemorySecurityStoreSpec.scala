package spoonbill.security.store

import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future, Promise}
import spoonbill.effect.Effect
import spoonbill.security.*
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*

class InMemorySecurityStoreSpec extends AsyncFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect

  private def uuid(n: Long): UUID = new UUID(0L, n)
  private val now                 = Instant.parse("2026-10-05T12:00:00Z")
  private val deadline            = now.plusSeconds(60)
  private val binding = SlotBinding(
    BrowserSessionSlotId.fromUuid(uuid(1)),
    BrowserBindingId.fromUuid(uuid(2)),
    RealmId.fromUuid(uuid(3)),
    CookieNamespace.fromUuid(uuid(4))
  )
  private val view        = ViewSessionId.fromUuid(uuid(5))
  private val ownerA      = ViewOwnerId.fromUuid(uuid(6))
  private val ownerB      = ViewOwnerId.fromUuid(uuid(7))
  private val invocationA = InvocationId.fromUuid(uuid(8))
  private val invocationB = InvocationId.fromUuid(uuid(9))
  private val operationBinding = OperationBinding(
    SubjectId.fromUuid(uuid(10)),
    binding.realmId,
    AuthSessionId.fromUuid(uuid(11)),
    SecurityGeneration.initial,
    OperationPurpose.fromUuid(uuid(12)),
    ResourceScope.fromUuid(uuid(13))
  )

  private def pending(n: Long): PendingActivation = PendingActivation(
    binding,
    SlotGeneration.initial,
    CompletionId.fromUuid(uuid(100 + n)),
    AuthSessionId.fromUuid(uuid(200 + n)),
    deadline
  )
  private def grant(
    n: Long,
    policy: ReservationPolicy = ReservationPolicy.ReleaseAfterDefiniteFailure
  ): GrantDefinition =
    GrantDefinition(OperationAuthorizationId.fromUuid(uuid(300 + n)), operationBinding, deadline, policy)
  private def accepted[E, A](result: Either[E, A]): A =
    result.fold(error => fail(s"Unexpected rejection: $error"), identity)

  /**
   * Actual worker-pool contention, not the test framework's serial callback
   * executor.
   */
  private def concurrently[A](operations: Vector[() => Future[A]]): Future[Vector[A]] = {
    val start   = Promise[Unit]()
    val running = operations.map(operation => start.future.flatMap(_ => operation())(ExecutionContext.global))
    start.success(())
    Future.sequence(running)
  }

  "The reference slot store" should "admit exactly one of concurrently activated anonymous completions" in {
    for {
      store    <- InMemorySecurityStore.create[Future]()
      _        <- store.createSlot(binding)
      outcomes <- concurrently((1L to 32L).toVector.map(n => () => store.activate(pending(n), now)))
      current  <- store.readSlot(binding.slotId)
    } yield {
      outcomes.count(_.isRight) shouldBe 1
      outcomes.collect { case Left(error) => error }.distinct shouldBe Vector(
        StoreError.SlotRejected(SlotError.StaleGeneration)
      )
      val winner = outcomes.collectFirst { case Right(slot) => slot }.getOrElse(fail("No activation won"))
      current shouldBe Right(winner)
      winner.generation.toLong shouldBe 1L
    }
  }

  it should "linearize logout against activation and reject a delayed completion afterwards" in {
    for {
      store       <- InMemorySecurityStore.create[Future]()
      _           <- store.createSlot(binding)
      outcomes    <- concurrently(Vector(() => store.activate(pending(1), now), () => store.logout(binding)))
      current     <- store.readSlot(binding.slotId)
      replay      <- store.activate(pending(1), now)
      staleCookie <- store.validateCurrent(binding, pending(1).sessionId, accepted(current).generation)
    } yield {
      outcomes(1).isRight shouldBe true
      accepted(current).current shouldBe None
      Set(1L, 2L) should contain(accepted(current).generation.toLong)
      replay shouldBe Left(StoreError.SlotRejected(SlotError.StaleGeneration))
      staleCookie shouldBe Left(StoreError.SlotRejected(SlotError.SessionNotCurrent))
    }
  }

  it should "never reset existing lineage through repeated creation" in {
    for {
      store     <- InMemorySecurityStore.create[Future]()
      _         <- store.createSlot(binding)
      active    <- store.activate(pending(1), now)
      recreated <- store.createSlot(binding)
      wrong     <- store.createSlot(binding.copy(browserBindingId = BrowserBindingId.fromUuid(uuid(99))))
      current   <- store.readSlot(binding.slotId)
    } yield {
      recreated shouldBe active
      wrong shouldBe Left(StoreError.SlotRejected(SlotError.BindingMismatch))
      current shouldBe active
    }
  }

  "View ownership storage" should "choose one owner and fence old-owner revision writes after takeover" in {
    for {
      store <- InMemorySecurityStore.create[Future]()
      _     <- store.createView(view)
      acquired <- concurrently(
                    (1L to 24L).toVector.map(n =>
                      () => store.acquireView(view, ViewOwnershipEpoch.initial, ViewOwnerId.fromUuid(uuid(500 + n)))
                    )
                  )
      oldFence        = acquired.collectFirst { case Right(fence) => fence }.getOrElse(fail("No owner won"))
      newFence       <- store.acquireView(view, oldFence.epoch, ownerB)
      staleWrite     <- store.advanceRevision(oldFence, ViewRevision.initial)
      staleRelease   <- store.releaseView(oldFence)
      changed        <- store.advanceRevision(accepted(newFence), ViewRevision.initial)
      duplicateWrite <- store.advanceRevision(accepted(newFence), ViewRevision.initial)
      current        <- store.readView(view)
    } yield {
      acquired.count(_.isRight) shouldBe 1
      staleWrite shouldBe Left(StoreError.OwnershipRejected(OwnershipError.StaleEpoch))
      staleRelease shouldBe Left(StoreError.OwnershipRejected(OwnershipError.StaleEpoch))
      accepted(changed).toLong shouldBe 1L
      duplicateWrite shouldBe Left(StoreError.RevisionMismatch)
      accepted(current).revision shouldBe accepted(changed)
      accepted(current).ownership.owner shouldBe Some(ownerB)
    }
  }

  it should "CAS concurrent receiver revisions instead of losing one update" in {
    for {
      store   <- InMemorySecurityStore.create[Future]()
      _       <- store.createView(view)
      fence   <- store.acquireView(view, ViewOwnershipEpoch.initial, ownerA)
      writes  <- concurrently(Vector.fill(16)(() => store.advanceRevision(accepted(fence), ViewRevision.initial)))
      current <- store.readView(view)
    } yield {
      writes.count(_.isRight) shouldBe 1
      writes.count(_ == Left(StoreError.RevisionMismatch)) shouldBe 15
      accepted(current).revision.toLong shouldBe 1L
    }
  }

  "Grant storage" should "return Acquired exactly once even for simultaneous same-invocation retries" in {
    val definition = grant(1)
    for {
      store     <- InMemorySecurityStore.create[Future]()
      _         <- store.issueGrant(definition)
      results   <- concurrently(Vector.fill(32)(() => store.reserve(definition.id, operationBinding, invocationA, now)))
      record    <- store.readInvocation(invocationA)
      _         <- store.markUnknown(invocationA)
      unknown   <- store.reserve(definition.id, operationBinding, invocationA, now)
      _         <- store.recordCommitted(invocationA)
      completed <- store.reserve(definition.id, operationBinding, invocationA, now)
    } yield {
      results.count(_ == Right(ReservationOutcome.Acquired)) shouldBe 1
      results.count(_ == Right(ReservationOutcome.InProgress)) shouldBe 31
      accepted(record).status shouldBe InvocationStatus.InProgress
      unknown shouldBe Right(ReservationOutcome.Unknown)
      completed shouldBe Right(ReservationOutcome.Committed)
    }
  }

  it should "reserve one grant for only one of competing invocation IDs" in {
    val definition = grant(1)
    for {
      store <- InMemorySecurityStore.create[Future]()
      _     <- store.issueGrant(definition)
      results <- concurrently(
                   (1L to 32L).toVector.map(n =>
                     () => store.reserve(definition.id, operationBinding, InvocationId.fromUuid(uuid(600 + n)), now)
                   )
                 )
    } yield {
      results.count(_ == Right(ReservationOutcome.Acquired)) shouldBe 1
      results.count(_ == Left(StoreError.GrantRejected(OperationAuthorizationError.CompetingInvocation))) shouldBe 31
    }
  }

  it should "retain an in-flight invocation and record a late commit after revocation and expiry" in {
    val definition = grant(1)
    for {
      store          <- InMemorySecurityStore.create[Future]()
      _              <- store.issueGrant(definition)
      _              <- store.reserve(definition.id, operationBinding, invocationA, now)
      _              <- store.markUnknown(invocationA)
      _              <- store.revokeGrant(definition.id)
      retried        <- store.reserve(definition.id, operationBinding, invocationA, deadline.plusSeconds(10))
      lateCommit     <- store.recordCommitted(invocationA)
      repeatedCommit <- store.recordCommitted(invocationA)
      other          <- store.reserve(definition.id, operationBinding, invocationB, deadline.plusSeconds(10))
    } yield {
      retried shouldBe Right(ReservationOutcome.Unknown)
      accepted(lateCommit).status shouldBe InvocationStatus.Committed
      repeatedCommit shouldBe lateCommit
      other shouldBe Left(StoreError.GrantRejected(OperationAuthorizationError.CompetingInvocation))
    }
  }

  it should "keep definite-failure invocation records terminal even when a grant is reused" in {
    val definition = grant(1)
    for {
      store            <- InMemorySecurityStore.create[Future]()
      _                <- store.issueGrant(definition)
      _                <- store.reserve(definition.id, operationBinding, invocationA, now)
      failed           <- store.releaseAfterDefiniteFailure(invocationA, now)
      retried          <- store.reserve(definition.id, operationBinding, invocationA, now)
      fresh            <- store.reserve(definition.id, operationBinding, invocationB, now)
      duplicateFailure <- store.releaseAfterDefiniteFailure(invocationA, now)
      contradictory    <- store.recordCommitted(invocationA)
      current          <- store.readGrant(definition.id)
    } yield {
      accepted(failed).status shouldBe InvocationStatus.NotCommitted
      retried shouldBe Right(ReservationOutcome.NotCommitted)
      fresh shouldBe Right(ReservationOutcome.Acquired)
      duplicateFailure shouldBe failed
      contradictory shouldBe Left(StoreError.OutcomeConflict)
      accepted(current).state shouldBe OperationAuthorizationState.Reserved(invocationB, None)
    }
  }

  it should "require reconciliation for unknown work and never refund a revoked reservation" in {
    val definition = grant(1)
    for {
      store      <- InMemorySecurityStore.create[Future]()
      _          <- store.issueGrant(definition)
      _          <- store.reserve(definition.id, operationBinding, invocationA, now)
      _          <- store.markUnknown(invocationA)
      premature  <- store.releaseAfterDefiniteFailure(invocationA, now)
      _          <- store.revokeGrant(definition.id)
      reconciled <- store.reconcileNotCommitted(invocationA, now)
      reused     <- store.reserve(definition.id, operationBinding, invocationB, now)
      replay     <- store.reserve(definition.id, operationBinding, invocationA, now)
    } yield {
      premature shouldBe Left(StoreError.GrantRejected(OperationAuthorizationError.ReconciliationRequired))
      accepted(reconciled).status shouldBe InvocationStatus.NotCommitted
      reused shouldBe Left(StoreError.GrantRejected(OperationAuthorizationError.Revoked))
      replay shouldBe Right(ReservationOutcome.NotCommitted)
    }
  }

  it should "burn consume-on-attempt grants without overwriting their issuance on retry" in {
    val definition = grant(1, ReservationPolicy.ConsumeOnAttempt)
    for {
      store       <- InMemorySecurityStore.create[Future]()
      _           <- store.issueGrant(definition)
      _           <- store.reserve(definition.id, operationBinding, invocationA, now)
      _           <- store.releaseAfterDefiniteFailure(invocationA, now)
      issuedAgain <- store.issueGrant(definition)
      reused      <- store.reserve(definition.id, operationBinding, invocationB, now)
      conflicting <- store.issueGrant(definition.copy(expiresAt = deadline.plusSeconds(60)))
    } yield {
      accepted(issuedAgain).state shouldBe OperationAuthorizationState.Invalidated(GrantInvalidation.Burned)
      reused shouldBe Left(StoreError.GrantRejected(OperationAuthorizationError.Burned))
      conflicting shouldBe Left(StoreError.ConflictingDefinition(RecordKind.Grant))
    }
  }

  it should "not reuse an invocation identity for a different grant or binding" in {
    val first  = grant(1)
    val second = grant(2)
    for {
      store    <- InMemorySecurityStore.create[Future]()
      _        <- store.issueGrant(first)
      _        <- store.issueGrant(second)
      _        <- store.reserve(first.id, operationBinding, invocationA, now)
      conflict <- store.reserve(second.id, operationBinding, invocationA, now)
      wrongBinding <-
        store.reserve(first.id, operationBinding.copy(subjectId = SubjectId.fromUuid(uuid(99))), invocationA, now)
      unaffected <- store.readGrant(second.id)
    } yield {
      conflict shouldBe Left(StoreError.InvocationConflict)
      wrongBinding shouldBe Left(StoreError.GrantRejected(OperationAuthorizationError.BindingMismatch))
      accepted(unaffected).state shouldBe OperationAuthorizationState.Available
    }
  }

  "Capacity limits" should "fail atomically without evicting completed invocation protection" in {
    val limits = accepted(StoreLimits.create(maxSlots = 1, maxViews = 1, maxGrants = 2, maxInvocations = 1))
    val first  = grant(1)
    val second = grant(2)
    for {
      store          <- InMemorySecurityStore.create[Future](limits)
      _              <- store.createSlot(binding)
      slotFull       <- store.createSlot(binding.copy(slotId = BrowserSessionSlotId.fromUuid(uuid(99))))
      _              <- store.createView(view)
      viewFull       <- store.createView(ViewSessionId.fromUuid(uuid(99)))
      _              <- store.issueGrant(first)
      _              <- store.issueGrant(second)
      grantFull      <- store.issueGrant(grant(3))
      _              <- store.reserve(first.id, operationBinding, invocationA, now)
      _              <- store.recordCommitted(invocationA)
      invocationFull <- store.reserve(second.id, operationBinding, invocationB, now)
      unaffected     <- store.readGrant(second.id)
      replay         <- store.reserve(first.id, operationBinding, invocationA, now)
      retained       <- store.readInvocation(invocationA)
    } yield {
      slotFull shouldBe Left(StoreError.CapacityExceeded(RecordKind.Slot))
      viewFull shouldBe Left(StoreError.CapacityExceeded(RecordKind.View))
      grantFull shouldBe Left(StoreError.CapacityExceeded(RecordKind.Grant))
      invocationFull shouldBe Left(StoreError.CapacityExceeded(RecordKind.Invocation))
      accepted(unaffected).state shouldBe OperationAuthorizationState.Available
      replay shouldBe Right(ReservationOutcome.Committed)
      accepted(retained).status shouldBe InvocationStatus.Committed
      StoreLimits.create(maxInvocations = 0) shouldBe Left(StoreConfigurationError.NonPositiveCapacity)
    }
  }
}
