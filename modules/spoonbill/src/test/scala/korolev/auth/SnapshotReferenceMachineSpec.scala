package spoonbill.auth

import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.Qsid
import spoonbill.recovery.*
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}

class SnapshotReferenceMachineSpec extends AnyFlatSpec with Matchers {
  import SnapshotReferenceMachine.*
  private val now                   = Instant.parse("2026-11-07T00:00:00Z")
  private def id(value: Long): UUID = new UUID(0L, value)
  private val qsid                  = Qsid("device", "view")
  private val owner                 = id(1)
  private val epoch                 = ViewOwnershipEpoch.fromLong(1).toOption.get
  private val settings              = SnapshotReferenceSettings()
  private val schema                = SnapshotSchema("counter", 1)
  private def staged(state: State[String], number: Long, expected: ViewRevision): (State[String], PublicationTicket) = {
    val (next, result) = reserve(
      state,
      qsid,
      owner,
      epoch,
      "identity",
      PublicationId(id(number)),
      expected,
      PayloadRef(id(number + 100), Vector.fill(32)(0.toByte), 0),
      schema,
      PublicationIntent.Save,
      false,
      now,
      settings
    )
    (next, result.toOption.get)
  }
  private def abandoned(): (State[String], PublicationId, Instant) = {
    val (reserved, ticket)  = staged(initial[String], 10, ViewRevision.initial)
    val deadline            = now.plusSeconds(settings.reservationSeconds)
    val (retired, eligible) = garbage(reserved, 1, deadline)
    eligible shouldBe Vector(ticket.operation)
    (retired, ticket.operation, deadline)
  }

  "Reference publication" should "retire superseded payloads atomically while pins protect their readers" in {
    val (reserved, first)        = staged(initial[String], 10, ViewRevision.initial)
    val (published, result)      = publish(reserved, first, qsid, owner, now)
    val revision                 = result.toOption.get
    val held                     = Pin(id(20), first.operation, qsid, owner, epoch.toLong, revision, now.plusSeconds(60))
    val (nextReserved, second)   = staged(pin(published, held), 11, revision)
    val (replaced, secondResult) = publish(nextReserved, second, qsid, owner, now)
    secondResult.toOption.get.toLong shouldBe 2L
    current(replaced, qsid).published shouldBe Some(second.operation)
    status(replaced, first.operation, owner, epoch, now) shouldBe PublicationStatus.Published(revision)
    garbage(replaced, 1, now)._2 shouldBe empty
    garbage(replaced, 1, held.expires)._2 shouldBe Vector(first.operation)
    backlog(replaced).open shouldBe 1L
  }

  it should "reject expired reservations at equality without losing their cleanup obligation" in {
    val (reserved, ticket) = staged(initial[String], 10, ViewRevision.initial)
    val deadline           = now.plusSeconds(settings.reservationSeconds)
    publish(reserved, ticket, qsid, owner, deadline) shouldBe ((reserved, Left(Error.StorageFailure)))
    status(reserved, ticket.operation, owner, epoch, deadline) shouldBe PublicationStatus.NotPublished
    val (retired, _) = garbage(reserved, 1, deadline)
    backlog(retired).open shouldBe 1L
    backlog(retired).staged shouldBe 0L
  }

  "Garbage ownership" should "reject a superseded claim and replay a completed claim without reopening it" in {
    val (retired, operation, time) = abandoned()
    val (claimed, firstValue)      = claim(retired, operation, id(30), time, settings.claimLeaseSeconds)
    val first                      = firstValue.get
    val (reclaimed, secondValue)   = claim(claimed, operation, id(31), first.expiresAt, settings.claimLeaseSeconds)
    val second                     = secondValue.get
    second.generation shouldBe 2L
    completeDelete(reclaimed, first) shouldBe ((reclaimed, ClaimUpdate.StaleClaim))
    val (deleted, applied) = completeDelete(reclaimed, second)
    applied shouldBe ClaimUpdate.Applied
    completeDelete(deleted, second) shouldBe ((deleted, ClaimUpdate.AlreadyApplied))
    retryDelete(deleted, second, time, settings.retryBackoffSeconds) shouldBe ((deleted, ClaimUpdate.AlreadyApplied))
    garbage(deleted, 1, second.expiresAt)._2 shouldBe empty
    retire(deleted, operation, second.expiresAt) shouldBe deleted
    backlog(deleted).open shouldBe 0L
  }

  it should "back off retries and distinguish new claims even when their generation restarts" in {
    val (retired, operation, time) = abandoned()
    val (claimed, firstValue)      = claim(retired, operation, id(30), time, settings.claimLeaseSeconds)
    val first                      = firstValue.get
    val (pending, applied)         = retryDelete(claimed, first, time, settings.retryBackoffSeconds)
    applied shouldBe ClaimUpdate.Applied
    backlog(pending).uncertain shouldBe 1L
    val ready = time.plusSeconds(settings.retryBackoffSeconds)
    garbage(pending, 1, ready.minusNanos(1))._2 shouldBe empty
    garbage(pending, 1, ready)._2 shouldBe Vector(operation)
    val (retried, nextValue) = claim(pending, operation, id(31), ready, settings.claimLeaseSeconds)
    nextValue.get.generation shouldBe 1L
    completeDelete(retried, first) shouldBe ((retried, ClaimUpdate.StaleClaim))
    completeDelete(retried, nextValue.get)._2 shouldBe ClaimUpdate.Applied
  }

  "Read ownership" should "preserve a foreign pin and release an owned pin even when its read is stale" in {
    val revision = ViewRevision.fromLong(1).toOption.get
    val held     = Pin(id(20), PublicationId(id(10)), qsid, owner, epoch.toLong, revision, now.plusSeconds(60))
    val pinned   = pin(initial[String], held)
    val readPin  = ReadPin(held.id, held.operation, held.revision)
    finishRead(pinned, readPin, qsid, id(2), 2L, now) shouldBe ((pinned, false))
    val (released, valid) = finishRead(pinned, readPin, qsid, owner, epoch.toLong, now)
    valid shouldBe false
    released.pins shouldBe empty
  }
}
