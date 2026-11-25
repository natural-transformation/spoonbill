package spoonbill.security.jdbc

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.security.Versions.ViewRevision

/** The pure phases of an inline session that can skip an unchanged projection. */
class JdbcSnapshotSessionMachineSpec extends AnyFlatSpec with Matchers {
  private val first  = ViewRevision.initial
  private val second = first.next.toOption.get
  private val bytes  = Array[Byte](1, 2, 3)

  "An inline snapshot session" should "skip only bytes equal to the acknowledged copy, at its own revision" in {
    val ready = JdbcSnapshotSessionMachine.acknowledged(second, bytes)
    JdbcSnapshotSessionMachine.unchanged(ready, Array[Byte](1, 2, 3)) shouldBe true
    JdbcSnapshotSessionMachine.unchanged(ready, Array[Byte](1, 2, 4)) shouldBe false
    JdbcSnapshotSessionMachine.conflicts(ready, second) shouldBe false
    JdbcSnapshotSessionMachine.conflicts(ready, first) shouldBe true
  }

  it should "keep its own copy of the acknowledged bytes" in {
    val written = Array[Byte](1, 2, 3)
    val ready   = JdbcSnapshotSessionMachine.acknowledged(second, written)
    written(0) = 9
    JdbcSnapshotSessionMachine.unchanged(ready, Array[Byte](1, 2, 3)) shouldBe true
  }

  it should "have no copy after a restore or while an attempt runs, and keep the revision until acknowledged" in {
    JdbcSnapshotSessionMachine.unchanged(JdbcSnapshotSessionMachine.restored(second), bytes) shouldBe false
    val ready   = JdbcSnapshotSessionMachine.acknowledged(second, bytes)
    val attempt = JdbcSnapshotSessionMachine.attempt(ready)
    JdbcSnapshotSessionMachine.unchanged(attempt, bytes) shouldBe false
    attempt.revision shouldBe second
    // Every change is a new value, so a late result of an older attempt is recognized by reference.
    (JdbcSnapshotSessionMachine.attempt(attempt) eq attempt) shouldBe false
  }
}
