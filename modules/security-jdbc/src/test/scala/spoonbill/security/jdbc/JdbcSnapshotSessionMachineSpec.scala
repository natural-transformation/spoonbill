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

  it should "have no copy after a restore or while a write runs, and keep the revision until acknowledged" in {
    JdbcSnapshotSessionMachine.unchanged(JdbcSnapshotSessionMachine.restored(second), bytes) shouldBe false
    val ready = JdbcSnapshotSessionMachine.acknowledged(second, bytes)
    JdbcSnapshotSessionMachine.begin(ready, second) match {
      case running: JdbcSnapshotSessionMachine.Ready =>
        JdbcSnapshotSessionMachine.unchanged(running, bytes) shouldBe false
        running.revision shouldBe second
      case other => fail(s"Expected a ready session, got $other")
    }
  }

  it should "publish an acknowledged write while the session still expects its revision, even after another write began" in {
    val third   = second.next.toOption.get
    val started = JdbcSnapshotSessionMachine.begin(JdbcSnapshotSessionMachine.acknowledged(second, bytes), second)
    // Another commit of the session began meanwhile; it expects the same revision.
    val overlapped = JdbcSnapshotSessionMachine.begin(started, second)
    JdbcSnapshotSessionMachine.acknowledge(overlapped, second, third, Array[Byte](4)) match {
      case published: JdbcSnapshotSessionMachine.Ready =>
        published.revision shouldBe third
        JdbcSnapshotSessionMachine.unchanged(published, Array[Byte](4)) shouldBe true
      case other => fail(s"Expected a published revision, got $other")
    }
    // A result for a revision the session no longer expects, or a session that is not ready, changes nothing.
    val later = JdbcSnapshotSessionMachine.acknowledged(third, bytes)
    JdbcSnapshotSessionMachine.acknowledge(later, second, third, Array[Byte](4)) should be theSameInstanceAs later
    val starting = new JdbcSnapshotSessionMachine.Starting
    JdbcSnapshotSessionMachine.acknowledge(starting, second, third, bytes) should be theSameInstanceAs starting
  }
}
