package spoonbill.recovery.testkit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.recovery.*
import spoonbill.security.Versions.ViewRevision
import spoonbill.snapshot.{SnapshotFormat, SnapshotLoad, SnapshotResetReason, ViewSnapshotError}

/**
 * The protocol laws of reference-backed recovery, run against any
 * RecoveryFixture: commit budget, takeover and revision fences, lost
 * replies, read pins, reclamation, garbage claims, late uploads, identity
 * and schema changes, missing payloads and backpressure. The modeled host
 * proves the protocol; a real provider fixture proves it against its store.
 */
trait RecoveryLaws[F[_], D] extends AnyFlatSpec with Matchers {
  import FaultDecision.*
  import RecoveryFault.*
  import ViewSnapshotError.*

  protected given effect: Effect[F]
  protected given executionContext: ExecutionContext = ExecutionContext.global
  protected def fixture(): RecoveryFixture[F, D]

  /** Distinct projection values. */
  protected def sample(index: Int): D

  /** A writer format the fixture's current format cannot read, and vice versa. */
  protected def incompatibleFormat: SnapshotFormat[D]
  protected def timeout: FiniteDuration = 30.seconds
  protected def await[A](value: F[A]): A = Await.result(effect.toFuture(value), timeout)

  private def accepted[E, A](value: Either[E, A]): A = value.fold(error => fail(s"Unexpected rejection: $error"), identity)
  private def rejection[A](value: Either[ViewSnapshotError, A]): ViewSnapshotError =
    value.fold(identity, result => fail(s"Unexpected success: $result"))
  private def restored(load: Either[ViewSnapshotError, SnapshotLoad[D]]): (Long, D) = accepted(load) match {
    case SnapshotLoad.Restored(revision, value) => (revision.toLong, value)
    case other                                  => fail(s"Expected restored data, got $other")
  }
  private def revision(value: Long): ViewRevision = ViewRevision.fromLong(value).toOption.get
  private val initial                             = ViewRevision.initial
  private val fenced                              = Set[ViewSnapshotError](StaleOwner, AccessDenied)
  private def background[A](work: => F[A]): F[A] = await(effect.start(work)).join()
  private def withFixture(body: RecoveryFixture[F, D] => Any): Unit = {
    val db = fixture()
    try body(db)
    finally await(db.close())
  }
  private def settled(db: RecoveryFixture[F, D]): Boolean = db.payloadBasis != CleanupBasis.Unproven
  private def pastReservation(db: RecoveryFixture[F, D]): F[Unit] =
    db.advanceClock((db.referenceSettings.reservationSeconds + 1).seconds)
  private def pastLease(db: RecoveryFixture[F, D]): F[Unit] =
    db.advanceClock((db.referenceSettings.claimLeaseSeconds + db.referenceSettings.retryBackoffSeconds + 1).seconds)

  "Reference-backed recovery" should "publish a save with one reservation, one durable put and one publication and restore it after takeover" in
    withFixture { db =>
      val first = await(db.open())
      val store = db.store(first)
      accepted(await(store.load())) shouldBe SnapshotLoad.Empty(initial)
      val before = await(db.observe(first)).operations
      accepted(await(store.save(initial, sample(1)))) shouldBe revision(1)
      val after = await(db.observe(first))
      after.operations.reservations - before.reservations shouldBe 1
      after.operations.publications - before.publications shouldBe 1
      after.operations.uploads - before.uploads shouldBe 1
      after.operations.statusLookups - before.statusLookups shouldBe 0
      after.operations.downloads - before.downloads shouldBe 0
      after.operations.deletes - before.deletes shouldBe 0
      after.revision shouldBe 1
      after.published shouldBe 1
      after.payloadPresent shouldBe Some(true)
      val second = await(db.takeOver(first))
      restored(await(db.store(second).load())) shouldBe (1L -> sample(1))
      fenced should contain(rejection(await(store.save(revision(1), sample(2)))))
      accepted(await(db.store(second).save(revision(1), sample(3)))) shouldBe revision(2)
      restored(await(db.store(second).load())) shouldBe (2L -> sample(3))
      await(db.observe(second)).published shouldBe 1
    }

  it should "reject a concurrent save of the same expected revision at publication without overwriting the winner" in
    withFixture { db =>
      val first = await(db.open())
      val store = db.store(first)
      accepted(await(store.save(initial, sample(1)))) shouldBe revision(1)
      val gate   = await(db.gate(AfterPayloadDurabilityBeforePublish))
      val paused = background(store.save(revision(1), sample(2)))
      await(gate.reached)
      accepted(await(store.save(revision(1), sample(3)))) shouldBe revision(2)
      await(gate.resume(Proceed))
      rejection(await(paused)) shouldBe RevisionConflict
      restored(await(store.load())) shouldBe (2L -> sample(3))
      await(db.observe(first)).staged shouldBe 1
      await(pastReservation(db))
      await(db.maintenance())
      val observed = await(db.observe(first))
      observed.staged shouldBe 0
      observed.published shouldBe 1
      if (settled(db)) observed.openObligations shouldBe 0 else observed.openObligations should be >= 1L
    }

  it should "fence an old owner's upload that completes after takeover and keep the successor's projection" in
    withFixture { db =>
      val first = await(db.open())
      accepted(await(db.store(first).save(initial, sample(1)))) shouldBe revision(1)
      val gate   = await(db.gate(AfterPayloadDurabilityBeforePublish))
      val paused = background(db.store(first).save(revision(1), sample(2)))
      await(gate.reached)
      val second = await(db.takeOver(first))
      restored(await(db.store(second).load())) shouldBe (1L -> sample(1))
      await(gate.resume(Proceed))
      fenced should contain(rejection(await(paused)))
      restored(await(db.store(second).load())) shouldBe (1L -> sample(1))
      accepted(await(db.store(second).save(revision(1), sample(3)))) shouldBe revision(2)
      await(pastReservation(db))
      await(db.maintenance())
      val observed = await(db.observe(second))
      observed.staged shouldBe 0
      if (settled(db)) observed.openObligations shouldBe 0
    }

  it should "recover a lost reservation reply through the original ticket instead of a second reservation" in
    withFixture { db =>
      val first  = await(db.open())
      val store  = db.store(first)
      val before = await(db.observe(first)).operations
      val gate   = await(db.gate(AfterReservationBeforeReply))
      val saving = background(store.save(initial, sample(1)))
      await(gate.reached)
      await(gate.resume(LoseReplyAfterCommit))
      accepted(await(saving)) shouldBe revision(1)
      val after = await(db.observe(first))
      after.operations.reservations - before.reservations shouldBe 1
      after.operations.statusLookups - before.statusLookups shouldBe 1
      after.operations.uploads - before.uploads shouldBe 1
      after.operations.publications - before.publications shouldBe 1
      restored(await(store.load())) shouldBe (1L -> sample(1))
    }

  it should "recover a lost publication reply to the committed revision and continue from it" in withFixture { db =>
    val first  = await(db.open())
    val store  = db.store(first)
    val before = await(db.observe(first)).operations
    val gate   = await(db.gate(AfterPublishBeforeReply))
    val saving = background(store.save(initial, sample(1)))
    await(gate.reached)
    await(gate.resume(LoseReplyAfterCommit))
    accepted(await(saving)) shouldBe revision(1)
    val after = await(db.observe(first))
    after.operations.statusLookups - before.statusLookups shouldBe 1
    after.operations.publications - before.publications shouldBe 1
    accepted(await(store.save(revision(1), sample(2)))) shouldBe revision(2)
    restored(await(store.load())) shouldBe (2L -> sample(2))
  }

  it should "fail closed when the process dies after publishing, so the stale expected revision conflicts next" in
    withFixture { db =>
      val first  = await(db.open())
      val store  = db.store(first)
      val gate   = await(db.gate(AfterPublishBeforeReply))
      val saving = background(store.save(initial, sample(1)))
      await(gate.reached)
      await(gate.resume(FailBeforeCommit))
      rejection(await(saving)) shouldBe StorageFailure
      await(db.observe(first)).revision shouldBe 1
      rejection(await(store.save(initial, sample(2)))) shouldBe RevisionConflict
      restored(await(store.load())) shouldBe (1L -> sample(1))
    }

  it should "reject adoption of fetched bytes after takeover or revocation during the fetch and release the pin" in
    withFixture { db =>
      val first = await(db.open())
      accepted(await(db.store(first).save(initial, sample(1)))) shouldBe revision(1)
      val second  = await(db.takeOver(first))
      val gate    = await(db.gate(AfterPayloadFetchBeforeAdoption))
      val loading = background(db.store(second).load())
      await(gate.reached)
      await(db.observe(second)).openPins shouldBe 1
      val third = await(db.takeOver(second))
      await(gate.resume(Proceed))
      fenced should contain(rejection(await(loading)))
      await(db.observe(third)).openPins shouldBe 0
      restored(await(db.store(third).load())) shouldBe (1L -> sample(1))
      val revokedGate = await(db.gate(AfterPayloadFetchBeforeAdoption))
      val revokedLoad = background(db.store(third).load())
      await(revokedGate.reached)
      await(db.revoke(third))
      await(revokedGate.resume(Proceed))
      fenced should contain(rejection(await(revokedLoad)))
      await(db.observe(third)).openPins shouldBe 0
    }

  it should "let a retired owner reconcile its own publication without regaining authority" in withFixture { db =>
    val first  = await(db.open())
    val gate   = await(db.gate(AfterPublishBeforeReply))
    val saving = background(db.store(first).save(initial, sample(1)))
    await(gate.reached)
    val second = await(db.takeOver(first))
    await(gate.resume(LoseReplyAfterCommit))
    accepted(await(saving)) shouldBe revision(1)
    fenced should contain(rejection(await(db.store(first).save(revision(1), sample(2)))))
    restored(await(db.store(second).load())) shouldBe (1L -> sample(1))
  }

  it should "retire the references of a reclaimed view and settle them only on the adapter's basis" in withFixture {
    db =>
      val first = await(db.open())
      accepted(await(db.store(first).save(initial, sample(1)))) shouldBe revision(1)
      await(db.reclaimView(first))
      val retired = await(db.observe(first))
      retired.published shouldBe 0
      retired.retired should be >= 1
      retired.openObligations should be >= 1L
      await(db.maintenance())
      val swept = await(db.observe(first))
      if (settled(db)) {
        swept.openObligations shouldBe 0
        swept.deleted should be >= 1
      } else {
        swept.openObligations should be >= 1L
        swept.uncertainObligations should be >= 1L
        swept.deleted shouldBe 0
      }
      fenced should contain(rejection(await(db.store(first).save(revision(1), sample(2)))))
  }

  it should "keep a pinned reference until its reader finishes, reject the stale read and then clean the reference" in
    withFixture { db =>
      val first = await(db.open())
      val store = db.store(first)
      accepted(await(store.save(initial, sample(1)))) shouldBe revision(1)
      val gate    = await(db.gate(AfterPayloadFetchBeforeAdoption))
      val loading = background(store.load())
      await(gate.reached)
      accepted(await(store.save(revision(1), sample(2)))) shouldBe revision(2)
      val before = await(db.observe(first))
      await(db.maintenance())
      val pinned = await(db.observe(first))
      pinned.openPins shouldBe 1
      pinned.operations.deletes - before.operations.deletes shouldBe 0
      pinned.openObligations shouldBe 1
      await(gate.resume(Proceed))
      rejection(await(loading)) shouldBe StaleOwner
      await(db.observe(first)).openPins shouldBe 0
      await(db.maintenance())
      val cleaned = await(db.observe(first))
      cleaned.operations.deletes - before.operations.deletes shouldBe 1
      if (settled(db)) cleaned.openObligations shouldBe 0 else cleaned.openObligations shouldBe 1
      restored(await(store.load())) shouldBe (2L -> sample(2))
    }

  it should "complete cleanup over a paused upload only with a retirement fence or proven quiescence" in withFixture {
    db =>
      val first  = await(db.open())
      val store  = db.store(first)
      val gate   = await(db.gate(BeforePayloadDispatch))
      val saving = background(store.save(initial, sample(1)))
      await(gate.reached)
      await(pastReservation(db))
      await(db.maintenance())
      val swept = await(db.observe(first))
      swept.staged shouldBe 0
      if (settled(db)) {
        swept.deleted shouldBe 1
        swept.openObligations shouldBe 0
      } else {
        swept.deleted shouldBe 0
        swept.openObligations shouldBe 1
      }
      await(gate.resume(Proceed))
      rejection(await(saving)) shouldBe StorageFailure
      val after = await(db.observe(first))
      after.revision shouldBe 0
      after.published shouldBe 0
      accepted(await(store.load())) shouldBe SnapshotLoad.Empty(initial)
      if (!settled(db)) {
        // A late write landed in an unproven store: the obligation stays open and charged.
        await(pastLease(db))
        await(db.maintenance())
        val retried = await(db.observe(first))
        retried.openObligations shouldBe 1
        retried.uncertainObligations shouldBe 1
        retried.deleted shouldBe 0
      }
  }

  it should "treat an uncertain upload as not durable and never publish it" in withFixture { db =>
    val first  = await(db.open())
    val store  = db.store(first)
    val gate   = await(db.gate(BeforePayloadDurability))
    val saving = background(store.save(initial, sample(1)))
    await(gate.reached)
    await(gate.resume(LoseReplyAfterCommit))
    rejection(await(saving)) shouldBe StorageFailure
    val observed = await(db.observe(first))
    observed.revision shouldBe 0
    observed.staged shouldBe 1
    accepted(await(store.load())) shouldBe SnapshotLoad.Empty(initial)
    await(pastReservation(db))
    await(db.maintenance())
    val swept = await(db.observe(first))
    swept.staged shouldBe 0
    if (settled(db)) swept.openObligations shouldBe 0 else swept.openObligations shouldBe 1
  }

  it should "keep an obligation whose completion was lost and settle it on a later idempotent delete" in withFixture {
    db =>
      if (!settled(db)) cancel("An unproven adapter never reaches completion; its obligations stay open by design")
      val first = await(db.open())
      val store = db.store(first)
      accepted(await(store.save(initial, sample(1)))) shouldBe revision(1)
      accepted(await(store.save(revision(1), sample(2)))) shouldBe revision(2)
      val gate  = await(db.gate(AfterDeleteBeforeComplete))
      val sweep = background(db.maintenance())
      await(gate.reached)
      await(gate.resume(FailBeforeCommit))
      await(sweep)
      val lost = await(db.observe(first))
      lost.openObligations shouldBe 1
      lost.deleted shouldBe 0
      await(pastLease(db))
      await(db.maintenance())
      val settledAfter = await(db.observe(first))
      settledAfter.openObligations shouldBe 0
      settledAfter.deleted shouldBe 1
      restored(await(store.load())) shouldBe (2L -> sample(2))
  }

  it should "transfer an expired garbage claim to another worker and reject the first worker's stale completion" in
    withFixture { db =>
      val first = await(db.open())
      val store = db.store(first)
      accepted(await(store.save(initial, sample(1)))) shouldBe revision(1)
      accepted(await(store.save(revision(1), sample(2)))) shouldBe revision(2)
      val gate   = await(db.gate(AfterGarbageClaimBeforeDelete))
      val sweep  = background(db.maintenance(0))
      await(gate.reached)
      await(pastLease(db))
      await(db.maintenance(1))
      val second = await(db.observe(first))
      if (settled(db)) second.deleted shouldBe 1
      await(gate.resume(Proceed))
      await(sweep)
      db.statistics(0).staleClaims shouldBe 1
      val after = await(db.observe(first))
      if (settled(db)) {
        after.deleted shouldBe 1
        after.openObligations shouldBe 0
      } else after.openObligations shouldBe 1
      restored(await(store.load())) shouldBe (2L -> sample(2))
    }

  it should "require an explicit reset after identity rotation and after an incompatible writer schema" in
    withFixture { db =>
      val first = await(db.open())
      accepted(await(db.store(first).save(initial, sample(1)))) shouldBe revision(1)
      val rotated = await(db.rotateIdentity(first))
      val store   = db.store(rotated)
      accepted(await(store.load())) shouldBe SnapshotLoad.ResetRequired(revision(1), SnapshotResetReason.IdentityChanged)
      rejection(await(store.save(revision(1), sample(2)))) shouldBe ResetRequired
      accepted(await(store.reset(revision(1), sample(2)))) shouldBe revision(2)
      restored(await(store.load())) shouldBe (2L -> sample(2))
      val upgraded = db.rebind(rotated, incompatibleFormat)
      accepted(await(upgraded.load())) shouldBe SnapshotLoad.ResetRequired(revision(2), SnapshotResetReason.SchemaChanged)
      rejection(await(upgraded.save(revision(2), sample(3)))) shouldBe ResetRequired
      accepted(await(upgraded.reset(revision(2), sample(3)))) shouldBe revision(3)
      rejection(await(upgraded.reset(revision(3), sample(3)))) shouldBe ResetNotRequired
      restored(await(upgraded.load())) shouldBe (3L -> sample(3))
      accepted(await(store.load())) shouldBe SnapshotLoad.ResetRequired(revision(3), SnapshotResetReason.SchemaChanged)
      await(db.maintenance())
      val observed = await(db.observe(rotated))
      observed.published shouldBe 1
      if (settled(db)) observed.openObligations shouldBe 0
    }

  it should "fail closed on a missing referenced payload without resetting or emptying the projection" in withFixture {
    db =>
      val first = await(db.open())
      val store = db.store(first)
      accepted(await(store.save(initial, sample(1)))) shouldBe revision(1)
      await(db.dropPayload(first))
      rejection(await(store.load())) shouldBe MalformedSnapshot
      rejection(await(store.reset(revision(1), sample(2)))) shouldBe ResetNotRequired
      await(db.observe(first)).revision shouldBe 1
  }

  it should "refuse new reservations when abandoned uploads reach the bound before their deadline" in withFixture { db =>
    val bound = db.referenceSettings.maxOpenObligations
    if (bound > 64) cancel("Needs a small obligation bound")
    val first  = await(db.open())
    val store  = db.store(first)
    val before = await(db.observe(first)).operations
    (1L to bound).foreach { index =>
      val gate   = await(db.gate(BeforePayloadDispatch))
      val saving = background(store.save(initial, sample(index.toInt)))
      await(gate.reached)
      await(gate.resume(FailBeforeCommit))
      rejection(await(saving)) shouldBe StorageFailure
    }
    val charged = await(db.observe(first))
    charged.staged shouldBe bound.toInt
    charged.operations.uploads - before.uploads shouldBe 0
    rejection(await(store.save(initial, sample(0)))) shouldBe StorageFailure
    await(db.observe(first)).staged shouldBe bound.toInt
    await(pastReservation(db))
    await(db.maintenance())
    val swept = await(db.observe(first))
    swept.staged shouldBe 0
    if (settled(db)) {
      swept.openObligations shouldBe 0
      accepted(await(store.save(initial, sample(1)))) shouldBe revision(1)
    } else swept.openObligations shouldBe bound
  }

  it should "refuse new reservations at the open-obligation bound instead of retaining payloads without limit" in
    withFixture { db =>
      val bound = db.referenceSettings.maxOpenObligations
      if (settled(db) || bound > 64) cancel("Needs an unproven adapter and a small obligation bound")
      val first = await(db.open())
      val store = db.store(first)
      (0L to bound).foreach(index => accepted(await(store.save(revision(index), sample(index.toInt)))))
      await(db.maintenance())
      val observed = await(db.observe(first))
      observed.openObligations shouldBe bound
      rejection(await(store.save(revision(bound + 1), sample(99)))) shouldBe StorageFailure
      restored(await(store.load())) shouldBe ((bound + 1) -> sample(bound.toInt))
    }
}
