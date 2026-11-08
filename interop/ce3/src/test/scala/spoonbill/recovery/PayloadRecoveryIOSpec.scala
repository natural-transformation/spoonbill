package spoonbill.recovery

import _root_.cats.effect.{Deferred, IO, Ref}
import _root_.cats.effect.unsafe.implicits.global
import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import spoonbill.auth.BrowserView
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect
import spoonbill.snapshot.{SchemaId, SchemaVersion, SnapshotFormat, StateSchema}

/**
 * Cancellation of a managed maintenance fiber with a cancelable effect. The
 * Future tests cannot show this: a fiber interrupted while an adapter delete
 * never settles must complete its cancellation at once, release the binding's
 * sweep, and leave the claimed obligation durable for a later sweep.
 */
final class PayloadRecoveryIOSpec extends AnyFlatSpec with Matchers {
  private given Effect[IO] = new IO3Effect(global)
  private case class Draft(text: String) derives StateSchema
  private val format =
    new SnapshotFormat[Draft](SchemaId.parse("draft").toOption.get, SchemaVersion.fromInt(1).toOption.get)
  private val claim = GarbageClaim(
    PublicationId.fresh(),
    PayloadRef(UUID.randomUUID(), Vector.fill(32)(0.toByte), 0),
    UUID.randomUUID(),
    1L,
    Instant.MAX
  )

  /** A store whose first delete waits on release and never settles unless released; later deletes settle at once. */
  private def store(entered: Deferred[IO, Unit], release: Deferred[IO, Unit], deletes: Ref[IO, Int]) =
    new SnapshotPayloadStore[IO] {
      def cleanupBasis: CleanupBasis                             = CleanupBasis.DurableRetirementFence
      def put(ref: PayloadRef, bytes: SnapshotBytes): IO[PayloadWrite] = IO.pure(PayloadWrite.Durable)
      def get(ref: PayloadRef): IO[PayloadRead]                  = IO.pure(PayloadRead.Missing)
      def delete(ref: PayloadRef): IO[PayloadDelete] = deletes.updateAndGet(_ + 1).flatMap { count =>
        if (count == 1) entered.complete(()) *> release.get.as(PayloadDelete.FinalDeleted)
        else IO.pure(PayloadDelete.FinalDeleted)
      }
    }

  private def provider(claims: Ref[IO, Int], completions: Ref[IO, Int]) = new SnapshotReferenceSupport[IO, String] {
    def bind(view: BrowserView[String], policy: SnapshotSchemaPolicy): Option[BoundSnapshotReferences[IO]] = None
    val maintenance: SnapshotReferenceMaintenance[IO] = new SnapshotReferenceMaintenance[IO] {
      def claimGarbage(limit: Int): IO[Vector[GarbageClaim]] = claims.update(_ + 1).as(Vector(claim))
      def completeDelete(value: GarbageClaim): IO[ClaimUpdate] = completions.update(_ + 1).as(ClaimUpdate.Applied)
      def retryDelete(value: GarbageClaim): IO[ClaimUpdate]    = IO.pure(ClaimUpdate.Applied)
      def backlog(): IO[CleanupBacklog]                         = IO.pure(CleanupBacklog(1L, 0L, 0L, None))
    }
  }

  private def binding(claims: Ref[IO, Int], completions: Ref[IO, Int], payloads: SnapshotPayloadStore[IO]) =
    PayloadRecovery.projectedWithPayloads[IO, String, Draft, Draft](
      provider(claims, completions),
      payloads,
      format,
      value => value,
      (_, saved) => saved
    )

  "A managed maintenance fiber" should "cancel promptly while an adapter delete never settles and record nothing" in {
    val test = for {
      entered     <- Deferred[IO, Unit]
      release     <- Deferred[IO, Unit]
      deletes     <- Ref.of[IO, Int](0)
      claims      <- Ref.of[IO, Int](0)
      completions <- Ref.of[IO, Int](0)
      target = binding(claims, completions, store(entered, release, deletes))
      fiber <- target.maintenance().start
      _     <- entered.get
      _     <- target.close()
      _     <- fiber.cancel.timeout(2.seconds)
      outcome <- fiber.join
      recorded <- completions.get
      claimed  <- claims.get
    } yield {
      outcome.isCanceled shouldBe true
      recorded shouldBe 0
      claimed shouldBe 1
      target.statistics.deletes shouldBe 1
    }
    test.unsafeRunTimed(10.seconds).getOrElse(fail("The cancellation test did not finish"))
  }

  it should "release the sweep on cancellation so a later sweep claims and completes again" in {
    val test = for {
      entered     <- Deferred[IO, Unit]
      release     <- Deferred[IO, Unit]
      deletes     <- Ref.of[IO, Int](0)
      claims      <- Ref.of[IO, Int](0)
      completions <- Ref.of[IO, Int](0)
      target = binding(claims, completions, store(entered, release, deletes))
      fiber <- target.maintenance().start
      _     <- entered.get
      _     <- fiber.cancel.timeout(2.seconds)
      _     <- target.maintenance()
      claimed  <- claims.get
      recorded <- completions.get
      attempts <- deletes.get
    } yield {
      claimed shouldBe 2
      recorded shouldBe 1
      attempts shouldBe 2
      target.statistics.finalDeletes shouldBe 1
    }
    test.unsafeRunTimed(10.seconds).getOrElse(fail("The cancellation test did not finish"))
  }
}
