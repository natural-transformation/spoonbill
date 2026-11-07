package spoonbill.security.jdbc

import java.sql.Timestamp
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.auth.{BrowserRetentionPolicy, BrowserView}
import spoonbill.effect.Effect
import spoonbill.recovery.*
import spoonbill.recovery.testkit.*
import spoonbill.security.Versions.ViewRevision
import spoonbill.snapshot.{SnapshotFormat, SnapshotLoad, ViewSnapshotError, ViewSnapshotException}

/** The protocol laws against real PostgreSQL with a fencing in-process adapter. */
class JdbcFencedRecoveryLawsSpec extends RecoveryLaws[Future, RecoveryCounter] {
  protected given effect: Effect[Future] = Effect.futureEffect
  protected def fixture(): RecoveryFixture[Future, RecoveryCounter] =
    new JdbcRecoveryFixture(new InMemoryPayloadStore[Future](CleanupBasis.DurableRetirementFence))
  protected def sample(index: Int): RecoveryCounter                 = RecoveryCounter(index)
  protected def incompatibleFormat: SnapshotFormat[RecoveryCounter] = JdbcRecoveryFixture.incompatible
}

/** The same laws with an adapter that cannot prove finality and a small obligation bound. */
class JdbcUnprovenRecoveryLawsSpec extends RecoveryLaws[Future, RecoveryCounter] {
  protected given effect: Effect[Future] = Effect.futureEffect
  protected def fixture(): RecoveryFixture[Future, RecoveryCounter] =
    new JdbcRecoveryFixture(
      new InMemoryPayloadStore[Future](CleanupBasis.Unproven),
      SnapshotReferenceSettings(maxOpenObligations = 4)
    )
  protected def sample(index: Int): RecoveryCounter                 = RecoveryCounter(index)
  protected def incompatibleFormat: SnapshotFormat[RecoveryCounter] = JdbcRecoveryFixture.incompatible
}

/** PostgreSQL-specific behavior beyond the generic laws. */
class JdbcSnapshotReferencesSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 20.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected rejection: $error"), identity)
  private def revision(value: Long): ViewRevision = ViewRevision.fromLong(value).toOption.get
  private val initial                             = ViewRevision.initial
  private def withFixture[A](body: JdbcRecoveryFixture => A): A = {
    val db = new JdbcRecoveryFixture(new InMemoryPayloadStore[Future]())
    try body(db)
    finally await(db.close())
  }
  private def snapshotRow(db: JdbcRecoveryFixture, view: db.View): (Long, Boolean, Int) =
    Using.resource(db.source.getConnection) { connection =>
      Using.resource(
        connection.prepareStatement(
          "SELECT revision,payload_operation IS NOT NULL,octet_length(payload) FROM spoonbill_view_snapshot WHERE realm=? AND view_id=?"
        )
      ) { query =>
        query.setString(1, db.realm); query.setString(2, BrowserView.key(view.view.qsid))
        Using.resource(query.executeQuery()) { rows =>
          rows.next() shouldBe true
          (rows.getLong(1), rows.getBoolean(2), rows.getInt(3))
        }
      }
    }

  "The JDBC publication capability" should "spend two provider transactions and one put on a healthy save" in withFixture {
    db =>
      val view  = await(db.open())
      val store = db.store(view)
      accepted(await(store.load())) shouldBe SnapshotLoad.Empty(initial)
      val before = (db.transactions.get(), db.harness.operations)
      accepted(await(store.save(initial, RecoveryCounter(1)))) shouldBe revision(1)
      db.transactions.get() - before._1 shouldBe 2
      val after = db.harness.operations
      after.uploads - before._2.uploads shouldBe 1
      after.statusLookups - before._2.statusLookups shouldBe 0
      after.downloads - before._2.downloads shouldBe 0
      snapshotRow(db, view) shouldBe ((1L, true, 0))
  }

  it should "serve an inline projection written before external payloads and move the view to a reference on the next publication" in
    withFixture { db =>
      val view   = await(db.open())
      val inline = db.persistence.projectedSnapshots[RecoveryCounter, RecoveryCounter](JdbcRecoveryFixture.format, value => value, (_, saved) => saved)(view.view).get
      await(inline.initialize(RecoveryCounter(0))) shouldBe RecoveryCounter(0)
      await(inline.commit(RecoveryCounter(5)))
      val inlineRow = snapshotRow(db, view)
      inlineRow._1 shouldBe 2L
      inlineRow._2 shouldBe false
      inlineRow._3 should be > 0
      val store = db.store(view)
      accepted(await(store.load())) shouldBe SnapshotLoad.Restored(revision(2), RecoveryCounter(5))
      db.harness.operations.downloads shouldBe 0
      accepted(await(store.save(revision(2), RecoveryCounter(6)))) shouldBe revision(3)
      snapshotRow(db, view) shouldBe ((3L, true, 0))
      // The inline session's expected revision is stale now; it cannot overwrite the reference.
      intercept[ViewSnapshotException](await(inline.commit(RecoveryCounter(7)))).error shouldBe ViewSnapshotError.RevisionConflict
      // An inline-only reader of a reference-backed revision fails closed instead of reading empty bytes.
      val successor = await(db.takeOver(view))
      val inlineReader = db.persistence.projectedSnapshots[RecoveryCounter, RecoveryCounter](JdbcRecoveryFixture.format, value => value, (_, saved) => saved)(successor.view).get
      intercept[ViewSnapshotException](await(inlineReader.initialize(RecoveryCounter(0)))).error shouldBe ViewSnapshotError.MalformedSnapshot
      snapshotRow(db, successor) shouldBe ((3L, true, 0))
      accepted(await(db.store(successor).load())) shouldBe SnapshotLoad.Restored(revision(3), RecoveryCounter(6))
    }

  it should "retire references in the retention sweep and reclaim settled rows and expired pins behind the history window" in
    withFixture { db =>
      val view  = await(db.open())
      val store = db.store(view)
      accepted(await(store.save(initial, RecoveryCounter(1)))) shouldBe revision(1)
      accepted(await(store.save(revision(1), RecoveryCounter(2)))) shouldBe revision(2)
      Using.resource(db.source.getConnection) { connection =>
        Using.resource(connection.prepareStatement(
          "INSERT INTO spoonbill_snapshot_read_pin(realm,cookie_namespace,pin_id,operation_id,view_id,owner_id,epoch,revision,expires_at) " +
            "SELECT realm,cookie_namespace,?,operation_id,view_id,owner_id,epoch,2,? FROM spoonbill_snapshot_reference WHERE realm=? AND state='published'"
        )) { query =>
          query.setObject(1, UUID.randomUUID()); query.setTimestamp(2, Timestamp.from(db.clock.get().plusSeconds(5)))
          query.setString(3, db.realm); query.executeUpdate() shouldBe 1
        }
      }
      await(db.observe(view)).openPins shouldBe 1
      await(db.maintenance())
      val swept = await(db.observe(view))
      swept.deleted shouldBe 1
      swept.openObligations shouldBe 0
      // Reclaiming the view runs the retention sweep an hour later: it retires the
      // current reference, drops the expired pin, and already reclaims the first
      // reference's settled row because the history window is shorter.
      await(db.reclaimView(view))
      val reclaimed = await(db.observe(view))
      reclaimed.published shouldBe 0
      reclaimed.retired shouldBe 1
      reclaimed.deleted shouldBe 0
      reclaimed.openPins shouldBe 0
      reclaimed.openObligations shouldBe 1
      await(db.maintenance())
      await(db.observe(view)).deleted shouldBe 1
      await(db.advanceClock((BrowserRetentionPolicy().historySeconds + BrowserRetentionPolicy().workIntervalSeconds + 1).seconds))
      await(db.persistence.maintenance())
      val reclaimedRows = await(db.observe(view))
      reclaimedRows.retired shouldBe 0
      reclaimedRows.deleted shouldBe 0
    }
}
