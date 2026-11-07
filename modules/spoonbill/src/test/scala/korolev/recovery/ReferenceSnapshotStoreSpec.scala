package spoonbill.recovery

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.auth.{BrowserCapabilityScope, BrowserView, HostPrincipal}
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.snapshot.*

/** The binding's protocol against scripted provider and adapter outcomes. */
class ReferenceSnapshotStoreSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private case class Draft(text: String) derives StateSchema
  private val format =
    new SnapshotFormat[Draft](SchemaId.parse("draft").toOption.get, SchemaVersion.fromInt(1).toOption.get)
  private val schema  = SnapshotSchema("draft", 1)
  private val initial = ViewRevision.initial
  private def revision(value: Long): ViewRevision = ViewRevision.fromLong(value).toOption.get
  private def encoded(value: Draft): SnapshotBytes =
    SnapshotBytes.copyOf(format.write(value).flatMap(SnapshotBinaryCodec.encode(_)).toOption.get)

  /** A scripted bound session: queues of answers, and a log of what was asked. */
  private final class FakeBound extends BoundSnapshotReferences[Future] {
    val ownerEpoch: ViewOwnershipEpoch = ViewOwnershipEpoch.initial
    var loads                          = List.empty[ReferenceLoad]
    var reservations                   = List.empty[PublicationTicket => ReservationOutcome]
    var publications                   = List.empty[PublicationOutcome]
    var statuses                       = List.empty[PublicationStatus]
    var finishes                       = List.empty[Either[ViewSnapshotError, Unit]]
    val released                       = new AtomicInteger()
    val finished                       = new AtomicInteger()
    val statusCalls                    = new AtomicInteger()
    val publishCalls                   = new AtomicInteger()
    val reserveCalls                   = new AtomicInteger()
    private def next[A](queue: List[A], name: String): (A, List[A]) = queue match {
      case head :: tail => (head, tail)
      case Nil          => fail(s"No scripted $name outcome")
    }
    def acquireRead(): Future[ReferenceLoad] = Future.successful {
      val (value, rest) = next(loads, "load"); loads = rest; value
    }
    def finishRead(pin: ReadPin): Future[Either[ViewSnapshotError, Unit]] = Future.successful {
      finished.incrementAndGet()
      val (value, rest) = next(finishes, "finish"); finishes = rest; value
    }
    def releaseRead(pin: ReadPin): Future[Unit] = Future.successful { released.incrementAndGet(); () }
    def reserve(
      operation: PublicationId,
      expected: ViewRevision,
      ref: PayloadRef,
      schema: SnapshotSchema,
      intent: PublicationIntent
    ): Future[ReservationOutcome] = Future.successful {
      reserveCalls.incrementAndGet()
      val (decide, rest) = next(reservations, "reserve"); reservations = rest
      decide(PublicationTicket(operation, ref, expected, intent, schema, ownerEpoch))
    }
    def publish(ticket: PublicationTicket): Future[PublicationOutcome] = Future.successful {
      publishCalls.incrementAndGet()
      val (value, rest) = next(publications, "publish"); publications = rest; value
    }
    def status(operation: PublicationId): Future[PublicationStatus] = Future.successful {
      statusCalls.incrementAndGet()
      val (value, rest) = next(statuses, "status"); statuses = rest; value
    }
  }

  private final class FakeStore extends SnapshotPayloadStore[Future] {
    var basis: CleanupBasis = CleanupBasis.DurableRetirementFence
    var objects             = Map.empty[UUID, SnapshotBytes]
    var write: Option[PayloadWrite]   = None
    var read: Option[PayloadRead]     = None
    var remove: Option[PayloadDelete] = None
    var pendingDelete: Option[Future[PayloadDelete]] = None
    var throwOnPut                    = false
    val puts                          = new AtomicInteger()
    val deletes                       = new AtomicInteger()
    def cleanupBasis: CleanupBasis    = basis
    def put(ref: PayloadRef, bytes: SnapshotBytes): Future[PayloadWrite] = {
      puts.incrementAndGet()
      if (throwOnPut) throw new IllegalStateException("Synthetic adapter failure")
      write.fold {
        objects += ref.id -> bytes; Future.successful(PayloadWrite.Durable)
      }(Future.successful)
    }
    def get(ref: PayloadRef): Future[PayloadRead] =
      Future.successful(read.getOrElse(objects.get(ref.id).fold[PayloadRead](PayloadRead.Missing)(PayloadRead.Found.apply)))
    def delete(ref: PayloadRef): Future[PayloadDelete] = {
      deletes.incrementAndGet()
      pendingDelete.getOrElse(Future.successful(remove.getOrElse { objects -= ref.id; PayloadDelete.FinalDeleted }))
    }
  }

  private def store(bound: FakeBound, payloads: FakeStore, closed: Boolean = false) =
    new ReferenceSnapshotStore[Future, Draft](
      bound,
      payloads,
      format,
      RecoveryProbe.none,
      new RecoveryCounters,
      () => closed
    )
  private val prepared: PublicationTicket => ReservationOutcome = ReservationOutcome.Prepared.apply

  "A reference-backed store" should "reserve, upload and publish a save with no status lookup on the healthy path" in {
    val bound    = new FakeBound
    val payloads = new FakeStore
    bound.reservations = List(prepared)
    bound.publications = List(PublicationOutcome.Published(revision(1)))
    await(store(bound, payloads).save(initial, Draft("a"))) shouldBe Right(revision(1))
    bound.reserveCalls.get() shouldBe 1
    payloads.puts.get() shouldBe 1
    bound.publishCalls.get() shouldBe 1
    bound.statusCalls.get() shouldBe 0
    payloads.objects.size shouldBe 1
  }

  it should "recover an uncertain reservation through status and upload its original ticket" in {
    val bound    = new FakeBound
    val payloads = new FakeStore
    var captured = Option.empty[PublicationTicket]
    bound.reservations = List(ticket => { captured = Some(ticket); ReservationOutcome.Uncertain })
    bound.statuses = List(PublicationStatus.Unresolved)
    await(store(bound, payloads).save(initial, Draft("a"))) shouldBe Left(ViewSnapshotError.StorageFailure)
    payloads.puts.get() shouldBe 0
    bound.reserveCalls.get() shouldBe 1
    bound.reservations = List(ticket => { captured = Some(ticket); ReservationOutcome.Uncertain })
    bound.statuses = List(PublicationStatus.Prepared(captured.get))
    bound.publications = List(PublicationOutcome.Published(revision(1)))
    await(store(bound, payloads).save(initial, Draft("a"))) shouldBe Right(revision(1))
    payloads.puts.get() shouldBe 1
    bound.statusCalls.get() shouldBe 2
  }

  it should "never publish after a write that is not durable" in {
    val bound    = new FakeBound
    val payloads = new FakeStore
    List(PayloadWrite.Uncertain, PayloadWrite.Unavailable, PayloadWrite.Conflict, PayloadWrite.Retired).foreach { outcome =>
      bound.reservations = List(prepared)
      payloads.write = Some(outcome)
      await(store(bound, payloads).save(initial, Draft("a"))) shouldBe Left(ViewSnapshotError.StorageFailure)
    }
    bound.publishCalls.get() shouldBe 0
    payloads.write = None
    payloads.throwOnPut = true
    bound.reservations = List(prepared)
    await(store(bound, payloads).save(initial, Draft("a"))) shouldBe Left(ViewSnapshotError.StorageFailure)
    bound.publishCalls.get() shouldBe 0
  }

  it should "reconcile an uncertain publication to its committed revision or fail closed" in {
    val bound    = new FakeBound
    val payloads = new FakeStore
    bound.reservations = List(prepared, prepared)
    bound.publications = List(PublicationOutcome.Uncertain, PublicationOutcome.Uncertain)
    bound.statuses = List(PublicationStatus.Published(revision(1)), PublicationStatus.Unresolved)
    await(store(bound, payloads).save(initial, Draft("a"))) shouldBe Right(revision(1))
    await(store(bound, payloads).save(revision(1), Draft("b"))) shouldBe Left(ViewSnapshotError.StorageFailure)
    bound.statusCalls.get() shouldBe 2
  }

  it should "surface provider rejections unchanged and reject an unencodable value before reserving" in {
    val bound    = new FakeBound
    val payloads = new FakeStore
    bound.reservations = List(_ => ReservationOutcome.Rejected(ViewSnapshotError.RevisionConflict), prepared)
    bound.publications = List(PublicationOutcome.Rejected(ViewSnapshotError.StaleOwner))
    await(store(bound, payloads).save(initial, Draft("a"))) shouldBe Left(ViewSnapshotError.RevisionConflict)
    await(store(bound, payloads).reset(initial, Draft("a"))) shouldBe Left(ViewSnapshotError.StaleOwner)
    val tight = new SnapshotFormat[Draft](
      format.schemaId,
      format.version,
      SnapshotLimits.create(maxUtf8Bytes = 64, maxNodes = 8, maxStringUtf8Bytes = 4).toOption.get
    )
    val narrow = new ReferenceSnapshotStore[Future, Draft](bound, payloads, tight, RecoveryProbe.none, new RecoveryCounters, () => false)
    await(narrow.save(initial, Draft("too long"))) shouldBe Left(ViewSnapshotError.InvalidState(SnapshotError.StringTooLarge))
    bound.reserveCalls.get() shouldBe 2
  }

  it should "restore pinned bytes only after integrity, schema and revalidation checks, releasing the pin on failure" in {
    val bound    = new FakeBound
    val payloads = new FakeStore
    val bytes    = encoded(Draft("saved"))
    val ref      = PayloadRef.fresh(bytes)
    payloads.objects += ref.id -> bytes
    val pin = ReadPin(UUID.randomUUID(), PublicationId.fresh(), revision(3))
    bound.loads = List(ReferenceLoad.Pinned(pin, ref, schema))
    bound.finishes = List(Right(()))
    await(store(bound, payloads).load()) shouldBe Right(SnapshotLoad.Restored(revision(3), Draft("saved")))
    bound.released.get() shouldBe 0
    // Wrong length or digest: never decoded, never adopted.
    bound.loads = List(ReferenceLoad.Pinned(pin, ref.copy(length = ref.length + 1), schema))
    await(store(bound, payloads).load()) shouldBe Left(ViewSnapshotError.MalformedSnapshot)
    bound.released.get() shouldBe 1
    bound.finished.get() shouldBe 1
    // Stored schema metadata disagreeing with the bytes.
    bound.loads = List(ReferenceLoad.Pinned(pin, ref, SnapshotSchema("draft", 2)))
    await(store(bound, payloads).load()) shouldBe Left(ViewSnapshotError.MalformedSnapshot)
    bound.released.get() shouldBe 2
    // Missing and unavailable bytes are failures, not an empty snapshot.
    bound.loads = List(ReferenceLoad.Pinned(pin, ref, schema))
    payloads.read = Some(PayloadRead.Missing)
    await(store(bound, payloads).load()) shouldBe Left(ViewSnapshotError.MalformedSnapshot)
    bound.loads = List(ReferenceLoad.Pinned(pin, ref, schema))
    payloads.read = Some(PayloadRead.Unavailable)
    await(store(bound, payloads).load()) shouldBe Left(ViewSnapshotError.StorageFailure)
    bound.released.get() shouldBe 4
    // Corrupt bytes under a matching reference.
    payloads.read = None
    val corrupt = SnapshotBytes.copyOf(Array[Byte](1, 2, 3))
    val corruptRef = PayloadRef.fresh(corrupt)
    payloads.objects += corruptRef.id -> corrupt
    bound.loads = List(ReferenceLoad.Pinned(pin, corruptRef, schema))
    await(store(bound, payloads).load()) shouldBe Left(ViewSnapshotError.MalformedSnapshot)
    bound.released.get() shouldBe 5
    // Refused revalidation releases the pin through the restricted path as well.
    bound.loads = List(ReferenceLoad.Pinned(pin, ref, schema))
    bound.finishes = List(Left(ViewSnapshotError.AccessDenied))
    await(store(bound, payloads).load()) shouldBe Left(ViewSnapshotError.AccessDenied)
    bound.released.get() shouldBe 6
  }

  it should "pass empty, inline, reset-required and rejected loads through" in {
    val bound    = new FakeBound
    val payloads = new FakeStore
    bound.loads = List(
      ReferenceLoad.Empty(initial),
      ReferenceLoad.Inline(revision(2), schema, encoded(Draft("inline"))),
      ReferenceLoad.Inline(revision(2), schema, SnapshotBytes.copyOf(Array[Byte](9))),
      ReferenceLoad.ResetRequired(revision(2), SnapshotResetReason.IdentityChanged),
      ReferenceLoad.Rejected(ViewSnapshotError.AccessDenied)
    )
    val target = store(bound, payloads)
    await(target.load()) shouldBe Right(SnapshotLoad.Empty(initial))
    await(target.load()) shouldBe Right(SnapshotLoad.Restored(revision(2), Draft("inline")))
    await(target.load()) shouldBe Left(ViewSnapshotError.MalformedSnapshot)
    await(target.load()) shouldBe Right(SnapshotLoad.ResetRequired(revision(2), SnapshotResetReason.IdentityChanged))
    await(target.load()) shouldBe Left(ViewSnapshotError.AccessDenied)
    bound.released.get() shouldBe 0
  }

  it should "fail closed once its binding is closed" in {
    val bound    = new FakeBound
    val payloads = new FakeStore
    bound.loads = List(ReferenceLoad.Empty(initial))
    bound.reservations = List(prepared)
    val closed = store(bound, payloads, closed = true)
    await(closed.load()) shouldBe Left(ViewSnapshotError.StorageFailure)
    await(closed.save(initial, Draft("a"))) shouldBe Left(ViewSnapshotError.StorageFailure)
    bound.reserveCalls.get() shouldBe 0
  }

  /** A scripted maintenance capability. */
  private final class FakeMaintenance extends SnapshotReferenceMaintenance[Future] {
    var claims                 = Vector.empty[GarbageClaim]
    var completions            = Vector.empty[GarbageClaim]
    var retries                = Vector.empty[GarbageClaim]
    var completeAnswer         = ClaimUpdate.Applied
    val claimRequests          = new AtomicInteger()
    var holdClaim              = Option.empty[Promise[Unit]]
    def claimGarbage(limit: Int): Future[Vector[GarbageClaim]] = {
      claimRequests.incrementAndGet()
      val batch = claims.take(limit)
      claims = claims.drop(limit)
      holdClaim.fold(Future.successful(batch))(_.future.map(_ => batch)(scala.concurrent.ExecutionContext.parasitic))
    }
    def completeDelete(claim: GarbageClaim): Future[ClaimUpdate] = Future.successful {
      completions :+= claim; completeAnswer
    }
    def retryDelete(claim: GarbageClaim): Future[ClaimUpdate] = Future.successful { retries :+= claim; ClaimUpdate.Applied }
    def backlog(): Future[CleanupBacklog] = Future.successful(CleanupBacklog(claims.size.toLong, 0L, 0L, None))
  }

  private def provider(fake: FakeMaintenance, bound: FakeBound) = new SnapshotReferenceSupport[Future, String] {
    def bind(view: BrowserView[String], policy: SnapshotSchemaPolicy): Option[BoundSnapshotReferences[Future]] =
      Some(bound)
    def maintenance: SnapshotReferenceMaintenance[Future] = fake
  }
  private def claim(): GarbageClaim = {
    val bytes = encoded(Draft("retired"))
    GarbageClaim(PublicationId.fresh(), PayloadRef.fresh(bytes), UUID.randomUUID(), 1L, Instant.MAX)
  }
  private def binding(
    maintenance: FakeMaintenance,
    payloads: FakeStore,
    bound: FakeBound = new FakeBound
  ): PayloadSnapshotBinding[Future, String, Draft] =
    PayloadRecovery.projectedWithPayloads[Future, String, Draft, Draft](
      provider(maintenance, bound),
      payloads,
      format,
      value => value,
      (_, saved) => saved,
      PayloadRecoverySettings(maintenanceBatch = 2)
    )

  "A payload binding" should "delete claimed references outside provider transactions and record only final deletions" in {
    val maintenance = new FakeMaintenance
    val payloads    = new FakeStore
    val finalClaim  = claim()
    val uncertain   = claim()
    maintenance.claims = Vector(finalClaim, uncertain)
    payloads.remove = None
    val target = binding(maintenance, payloads)
    await(target.maintenance())
    maintenance.completions shouldBe Vector(finalClaim, uncertain)
    payloads.deletes.get() shouldBe 2
    maintenance.claims = Vector(claim())
    payloads.remove = Some(PayloadDelete.Uncertain)
    await(target.maintenance())
    maintenance.completions.size shouldBe 2
    maintenance.retries.size shouldBe 1
    maintenance.claims = Vector(claim())
    payloads.remove = Some(PayloadDelete.Unavailable)
    await(target.maintenance())
    maintenance.retries.size shouldBe 2
    val statistics = target.statistics
    statistics.deletes shouldBe 4
    statistics.finalDeletes shouldBe 2
    statistics.uncertainDeletes shouldBe 2
  }

  it should "downgrade a FinalDeleted answer from an unproven adapter and keep the obligation" in {
    val maintenance = new FakeMaintenance
    val payloads    = new FakeStore
    payloads.basis = CleanupBasis.Unproven
    maintenance.claims = Vector(claim())
    val target = binding(maintenance, payloads)
    await(target.maintenance())
    maintenance.completions shouldBe empty
    maintenance.retries.size shouldBe 1
    target.statistics.downgradedDeletes shouldBe 1
  }

  it should "count stale completions without recording them as final deletions" in {
    val maintenance = new FakeMaintenance
    val payloads    = new FakeStore
    maintenance.claims = Vector(claim(), claim())
    maintenance.completeAnswer = ClaimUpdate.StaleClaim
    val target = binding(maintenance, payloads)
    await(target.maintenance())
    maintenance.completions.size shouldBe 2
    target.statistics.staleClaims shouldBe 2
    target.statistics.finalDeletes shouldBe 0
  }

  it should "coalesce overlapping sweeps and close without awaiting a delete that never settles" in {
    val maintenance = new FakeMaintenance
    val payloads    = new FakeStore
    maintenance.claims = Vector(claim(), claim())
    val stuck = Promise[PayloadDelete]()
    payloads.pendingDelete = Some(stuck.future)
    val target  = binding(maintenance, payloads)
    val running = target.maintenance()
    await(target.maintenance())
    maintenance.claimRequests.get() shouldBe 1
    running.isCompleted shouldBe false
    await(target.close())
    running.isCompleted shouldBe false
    maintenance.completions shouldBe empty
    await(target.maintenance())
    maintenance.claimRequests.get() shouldBe 1
    // The in-flight delete settles later: its completion is still recorded, the rest of the batch is skipped.
    stuck.success(PayloadDelete.FinalDeleted)
    await(running)
    maintenance.completions.size shouldBe 1
    payloads.deletes.get() shouldBe 1
  }

  it should "hand sessions of a closed binding a store that fails closed" in {
    val maintenance = new FakeMaintenance
    val payloads    = new FakeStore
    val bound       = new FakeBound
    bound.loads = List(ReferenceLoad.Empty(initial), ReferenceLoad.Empty(initial))
    bound.reservations = List(prepared, prepared)
    bound.publications = List(PublicationOutcome.Published(revision(1)), PublicationOutcome.Published(revision(1)))
    val target = binding(maintenance, payloads, bound)
    val scope  = new BrowserCapabilityScope[String]
    val identity =
      scope.identity("b" * 43, None, 1L, Some(HostPrincipal("alice", "subject", 1L, 1L, Instant.MAX)), Some(UUID.randomUUID()))
    val view    = scope.view(identity, Qsid("device", "view"), ConnectionId.fromUuid(UUID.randomUUID()), UUID.randomUUID(), 1L)
    val session = target.snapshots(view).getOrElse(fail("No session"))
    await(session.initialize(Draft("fresh"))) shouldBe Draft("fresh")
    await(target.close())
    val late = target.snapshots(view).getOrElse(fail("No session after close"))
    intercept[ViewSnapshotException](await(late.initialize(Draft("fresh")))).error shouldBe ViewSnapshotError.StorageFailure
    intercept[ViewSnapshotException](await(session.commit(Draft("next")))).error shouldBe ViewSnapshotError.StorageFailure
  }
}
